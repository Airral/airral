package com.airral.mcp;

import java.net.InetSocketAddress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Makes mcp.airral.com mean something.
 *
 * <p>A Cloud Run domain mapping binds a hostname to a service and cannot filter
 * by path, so mapping mcp.airral.com at the API service makes the entire REST
 * API answer on that hostname too. No security is lost -- the same credential
 * is still required -- but the two hostnames become interchangeable, and
 * anything built against mcp.airral.com/api/... breaks on the day the MCP
 * endpoint moves to its own deployable. Which is the whole reason the hostname
 * exists: users put it in a config file now so that move costs them nothing.
 *
 * <p>So the boundary is enforced here instead. The MCP hostname serves /mcp and
 * the health check, and nothing else. Behaviour today therefore already matches
 * behaviour after the split.
 *
 * <p>Runs before Spring Security: a request that does not belong on this
 * hostname should not reach authentication at all.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpHostFilter implements WebFilter {

    private final String mcpHostname;

    public McpHostFilter(@Value("${airral.mcp.hostname:}") String mcpHostname) {
        this.mcpHostname = mcpHostname == null ? "" : mcpHostname.trim().toLowerCase();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (mcpHostname.isEmpty()) {
            return chain.filter(exchange);
        }

        String host = requestHost(exchange);
        if (host == null || !host.equalsIgnoreCase(mcpHostname)) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (path.equals("/mcp") || path.startsWith("/mcp/") || path.equals("/actuator/health")) {
            return chain.filter(exchange);
        }

        // 404 rather than 403: on this hostname the path genuinely does not
        // exist, and saying "forbidden" would imply it is there to be reached
        // with different credentials.
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    /**
     * The hostname the client asked for, without its port.
     *
     * <p>Not {@code String.valueOf(getHeaders().getHost())}. That stringifies an
     * InetSocketAddress as {@code mcp.airral.com/<unresolved>:443}, which
     * matches no hostname and silently disabled this whole filter -- and Cloud
     * Run does not send X-Forwarded-Host, so the broken branch was the only one
     * ever taken. getHostString() returns the name alone.
     *
     * <p>X-Forwarded-Host is still preferred where a proxy does set it, and only
     * its first entry is read: the header accumulates a comma-separated trail
     * through a proxy chain, and the client's own value is at the front.
     */
    private static String requestHost(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Host");
        if (forwarded != null && !forwarded.isBlank()) {
            return stripPort(forwarded.split(",")[0].trim());
        }
        InetSocketAddress hostHeader = exchange.getRequest().getHeaders().getHost();
        return hostHeader == null ? null : hostHeader.getHostString();
    }

    private static String stripPort(String host) {
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
