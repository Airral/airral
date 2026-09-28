package com.airral.config;

import com.airral.service.TeamAlerts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ServerErrorAlertFilterTest {

    private final TeamAlerts alerts = mock(TeamAlerts.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
    private final ServerErrorAlertFilter filter = new ServerErrorAlertFilter(alerts, clock);

    /** A handler that answers with this status, the way GlobalExceptionHandler answers a 500. */
    private static WebFilterChain answering(HttpStatus status) {
        return exchange -> {
            if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
                exchange.getAttributes().put(ServerErrorAlertFilter.ERROR_TYPE, "NullPointerException");
                exchange.getAttributes().put(ServerErrorAlertFilter.ERROR_REFERENCE, "ab12cd34");
            }
            exchange.getResponse().setStatusCode(status);
            return Mono.empty();
        };
    }

    private static ServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }

    @Test
    @DisplayName("a server error on sign-in reaches Slack with its type and reference")
    void alertsOnWatchedRoutes() {
        filter.filter(post("/api/auth/login"), answering(HttpStatus.INTERNAL_SERVER_ERROR)).block();

        verify(alerts).serverError("POST", "/api/auth/login", 500, "NullPointerException", "ab12cd34", 0);
    }

    @Test
    @DisplayName("the same failure alerts once every ten minutes, then says how many more there were")
    void throttled() {
        for (int i = 0; i < 4; i++) {
            filter.filter(post("/api/applications"), answering(HttpStatus.INTERNAL_SERVER_ERROR)).block();
        }
        verify(alerts).serverError(eq("POST"), eq("/api/applications"), eq(500), anyString(), anyString(), eq(0));

        clock.advance(Duration.ofMinutes(11));
        filter.filter(post("/api/applications"), answering(HttpStatus.INTERNAL_SERVER_ERROR)).block();
        verify(alerts).serverError(eq("POST"), eq("/api/applications"), eq(500), anyString(), anyString(), eq(3));
    }

    @Test
    @DisplayName("client errors, and routes outside the watched ones, stay quiet")
    void quietOtherwise() {
        filter.filter(post("/api/auth/login"), answering(HttpStatus.UNAUTHORIZED)).block();
        filter.filter(post("/api/applications"), answering(HttpStatus.CONFLICT)).block();
        filter.filter(post("/api/jobs"), answering(HttpStatus.INTERNAL_SERVER_ERROR)).block();

        verify(alerts, never()).serverError(anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("an error nobody handled is reported and still reaches the caller")
    void unhandledError() {
        StepVerifier.create(filter.filter(post("/api/users/invite"), exchange -> Mono.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class)
                .verify();

        verify(alerts).serverError(eq("POST"), eq("/api/users/invite"), eq(500), eq("IllegalStateException"), isNull(), eq(0));
    }

    @Test
    @DisplayName("an invitation token in the path never reaches the alert")
    void tokensAreRedacted() {
        filter.filter(post("/api/auth/invitations/3f9c2a7b41e84d0c9a1b2c3d4e5f6a7b/accept"),
                answering(HttpStatus.INTERNAL_SERVER_ERROR)).block();

        verify(alerts).serverError(eq("POST"), eq("/api/auth/invitations/{token}/accept"), eq(500), anyString(), anyString(), eq(0));
        assertThat(ServerErrorAlertFilter.redact("/api/applications/123/resume")).isEqualTo("/api/applications/{id}/resume");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
