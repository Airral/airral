package com.airral.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * These tests exist because the first version of this filter did nothing at all
 * in production while looking correct in review, and nothing caught it until a
 * request to /api/feed on the MCP hostname answered 200.
 */
class McpHostFilterTest {

    private static final String MCP_HOST = "mcp.airral.com";

    /** Records whether the request was allowed through. */
    private static class Chain implements org.springframework.web.server.WebFilterChain {
        boolean passed;

        @Override
        public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
            passed = true;
            return Mono.empty();
        }
    }

    private Chain run(McpHostFilter filter, String host, String path, String forwardedHost) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (host != null) {
            builder.header("Host", host);
        }
        if (forwardedHost != null) {
            builder.header("X-Forwarded-Host", forwardedHost);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        Chain chain = new Chain();
        filter.filter(exchange, chain).block();
        // Status is only set on the rejection path.
        chain.passed = chain.passed && exchange.getResponse().getStatusCode() == null;
        if (!chain.passed) {
            assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        }
        return chain;
    }

    @Test
    @DisplayName("the Host header alone is enough to identify the MCP hostname")
    void hostHeaderIsRead() {
        // The original bug: the Host header was stringified from an
        // InetSocketAddress as "mcp.airral.com/<unresolved>:443", matched
        // nothing, and every request sailed through. Cloud Run sends no
        // X-Forwarded-Host, so this was the only path ever taken.
        McpHostFilter filter = new McpHostFilter(MCP_HOST);

        assertTrue(!run(filter, MCP_HOST, "/api/feed", null).passed,
                "the REST API must not answer on the MCP hostname");
    }

    @Test
    @DisplayName("/mcp is served on the MCP hostname")
    void mcpPathIsAllowed() {
        McpHostFilter filter = new McpHostFilter(MCP_HOST);
        assertTrue(run(filter, MCP_HOST, "/mcp", null).passed);
    }

    @Test
    @DisplayName("the health check still answers, so Cloud Run can probe it")
    void healthCheckIsAllowed() {
        // Without this the startup probe fails and the revision never serves.
        McpHostFilter filter = new McpHostFilter(MCP_HOST);
        assertTrue(run(filter, MCP_HOST, "/actuator/health", null).passed);
    }

    @Test
    @DisplayName("every other hostname is untouched")
    void otherHostsAreUnaffected() {
        McpHostFilter filter = new McpHostFilter(MCP_HOST);
        assertTrue(run(filter, "api.airral.com", "/api/feed", null).passed);
        assertTrue(run(filter, "api.airral.com", "/mcp", null).passed,
                "/mcp stays reachable on the API host, so an existing config keeps working");
    }

    @Test
    @DisplayName("a port on the Host header does not defeat the match")
    void portIsIgnored() {
        McpHostFilter filter = new McpHostFilter(MCP_HOST);
        assertTrue(!run(filter, MCP_HOST + ":443", "/api/feed", null).passed);
    }

    @Test
    @DisplayName("X-Forwarded-Host wins, and only its first entry is read")
    void forwardedHostTakesPrecedence() {
        McpHostFilter filter = new McpHostFilter(MCP_HOST);

        // A proxy chain appends to this header; the client's own value is first.
        assertTrue(!run(filter, "internal-lb", "/api/feed", MCP_HOST + ", proxy-1, proxy-2").passed);
        assertTrue(run(filter, MCP_HOST, "/api/feed", "api.airral.com").passed,
                "a forwarded host that is not the MCP one leaves the request alone");
    }

    @Test
    @DisplayName("an unset hostname disables the filter entirely")
    void blankHostnameDisablesFiltering() {
        // Local development serves everything from one origin.
        McpHostFilter filter = new McpHostFilter("");
        assertTrue(run(filter, MCP_HOST, "/api/feed", null).passed);

        McpHostFilter nullConfigured = new McpHostFilter(null);
        assertTrue(run(nullConfigured, MCP_HOST, "/api/feed", null).passed);
    }

    @Test
    @DisplayName("a path merely starting with mcp is not treated as /mcp")
    void similarPathsAreNotConfused() {
        McpHostFilter filter = new McpHostFilter(MCP_HOST);
        assertTrue(!run(filter, MCP_HOST, "/mcpanything", null).passed,
                "/mcpanything is not under /mcp");
        assertTrue(run(filter, MCP_HOST, "/mcp/session", null).passed,
                "but a genuine sub-path is");
    }

    @Test
    @DisplayName("a missing Host header does not throw")
    void missingHostIsTolerated() {
        McpHostFilter filter = new McpHostFilter(MCP_HOST);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed").build());
        Chain chain = new Chain();
        filter.filter(exchange, chain).block();
        assertTrue(chain.passed);
        assertNull(exchange.getResponse().getStatusCode());
    }
}
