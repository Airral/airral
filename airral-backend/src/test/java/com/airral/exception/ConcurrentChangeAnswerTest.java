package com.airral.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A save that lost a race is answered as a conflict to reload from, not as a server fault. */
class ConcurrentChangeAnswerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<Map<String, Object>> answer(RuntimeException error) {
        return handler.handleConcurrentChange(error,
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/offers/5/accept").build())).block();
    }

    @Test
    @DisplayName("a failed version check and a duplicate of a one-only row are both 409s with a plain message")
    void raceIsAConflict() {
        for (RuntimeException lost : new RuntimeException[] {
                new OptimisticLockingFailureException("Failed to update table [offers]; Version does not match"),
                new DuplicateKeyException("duplicate key value violates unique constraint \"uq_offers_one_open_per_application\"")}) {
            ResponseEntity<Map<String, Object>> response = answer(lost);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat((String) response.getBody().get("message"))
                    .startsWith("This was changed at the same moment somewhere else")
                    .doesNotContain("offers");
        }
    }
}
