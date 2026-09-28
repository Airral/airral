package com.airral.config;

import com.airral.service.TeamAlerts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tells the team in Slack when sign-in, signup, invitations or applications
 * fail on the server, so a broken path is noticed in minutes rather than when
 * someone complains.
 *
 * <p>An alert names the method, the route and the status, and the error's type
 * and reference when the exception handler recorded them. Never the request
 * itself: bodies carry passwords and resumes, and paths can carry invitation
 * tokens, so a path is reduced to its route. The same route and status alert
 * at most once every ten minutes per instance, and the next alert says how
 * many more there were.
 */
@Component
public class ServerErrorAlertFilter implements WebFilter {

    /** Set by the exception handler on a 500: the exception's simple class name. */
    public static final String ERROR_TYPE = ServerErrorAlertFilter.class.getName() + ".errorType";
    /** Set by the exception handler on a 500: the reference also in the log and the response. */
    public static final String ERROR_REFERENCE = ServerErrorAlertFilter.class.getName() + ".reference";

    static final List<String> WATCHED = List.of("/api/auth/", "/api/applications", "/api/users/invite",
            "/api/users/invitations");
    static final Duration QUIET = Duration.ofMinutes(10);

    private final TeamAlerts alerts;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(Instant alertedAt, AtomicInteger since) {}

    @Autowired
    public ServerErrorAlertFilter(TeamAlerts alerts) {
        this(alerts, Clock.systemUTC());
    }

    ServerErrorAlertFilter(TeamAlerts alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (WATCHED.stream().noneMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange)
                .doOnSuccess(done -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    if (status != null && status.is5xxServerError()) {
                        report(exchange, status.value(), exchange.getAttribute(ERROR_TYPE));
                    }
                })
                .doOnError(error -> report(exchange, 500, error.getClass().getSimpleName()));
    }

    private void report(ServerWebExchange exchange, int status, String errorType) {
        String method = exchange.getRequest().getMethod().name();
        String route = routeOf(exchange);
        String key = method + " " + route + " " + status;
        Instant now = clock.instant();

        AtomicInteger earlier = new AtomicInteger(-1);
        windows.compute(key, (k, window) -> {
            if (window == null || !now.isBefore(window.alertedAt().plus(QUIET))) {
                earlier.set(window == null ? 0 : window.since().get());
                return new Window(now, new AtomicInteger());
            }
            window.since().incrementAndGet();
            return window;
        });
        if (earlier.get() >= 0) {
            alerts.serverError(method, route, status, errorType, exchange.getAttribute(ERROR_REFERENCE), earlier.get());
        }
    }

    /**
     * The route that served the request, like /api/auth/invitations/{token}/accept,
     * or the path with anything that looks like an id or a token taken out.
     */
    public static String routeOf(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof PathPattern pathPattern) {
            return pathPattern.getPatternString();
        }
        if (pattern instanceof String text && !text.isBlank()) {
            return text;
        }
        return redact(exchange.getRequest().getPath().pathWithinApplication().value());
    }

    static String redact(String path) {
        return Stream.of(path.split("/", -1))
                .map(segment -> segment.matches("\\d+") ? "{id}"
                        : segment.length() > 24 || segment.matches(".*\\d.*[A-Za-z].*\\d.*") ? "{token}"
                        : segment)
                .collect(Collectors.joining("/"));
    }
}
