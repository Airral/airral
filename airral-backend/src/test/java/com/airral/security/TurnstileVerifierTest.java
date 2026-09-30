package com.airral.security;

import com.airral.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether an employer sign-up came from a person: the answer from Cloudflare, stubbed. */
class TurnstileVerifierTest {

    private final List<ClientRequest> calls = new ArrayList<>();

    private TurnstileVerifier answering(HttpStatus status, String body) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.add(request);
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
        return new TurnstileVerifier(builder, "test-secret");
    }

    @Test
    @DisplayName("without a secret the check is off, and Cloudflare is never asked")
    void offWithoutASecret() {
        TurnstileVerifier off = TurnstileVerifier.off();

        StepVerifier.create(off.check(null, "203.0.113.9")).verifyComplete();
        assertThat(off.enabled()).isFalse();
    }

    @Test
    @DisplayName("a token Cloudflare accepts lets the sign-up through, and goes to Cloudflare's address")
    void acceptedToken() {
        TurnstileVerifier verifier = answering(HttpStatus.OK, "{\"success\":true,\"hostname\":\"airral.com\"}");

        StepVerifier.create(verifier.check("token-from-the-widget", "203.0.113.9")).verifyComplete();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).url().toString()).isEqualTo(TurnstileVerifier.VERIFY_URL);
    }

    @Test
    @DisplayName("a token Cloudflare refuses, or no token at all, is refused with a message a person can act on")
    void refusedToken() {
        TurnstileVerifier verifier = answering(HttpStatus.OK, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

        StepVerifier.create(verifier.check("forged", "203.0.113.9"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage(TurnstileVerifier.REFUSED))
                .verify();
        StepVerifier.create(verifier.check("  ", "203.0.113.9")).expectError(BadRequestException.class).verify();
        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("Cloudflare not answering is not the person's fault: the sign-up goes through")
    void cloudflareDown() {
        TurnstileVerifier verifier = answering(HttpStatus.SERVICE_UNAVAILABLE, "{}");

        StepVerifier.create(verifier.check("token-from-the-widget", "203.0.113.9")).verifyComplete();
    }
}
