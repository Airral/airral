package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Workday is read slowly and a refused page is retried, not given up on. */
class WorkdayThrottleTest {

    @Test
    @DisplayName("rate limits and brief outages are retried; real errors are not")
    void retryable() {
        assertThat(WorkdayJobBoardClient.isRetryable(429)).isTrue();
        assertThat(WorkdayJobBoardClient.isRetryable(502)).isTrue();
        assertThat(WorkdayJobBoardClient.isRetryable(503)).isTrue();
        assertThat(WorkdayJobBoardClient.isRetryable(504)).isTrue();
        assertThat(WorkdayJobBoardClient.isRetryable(400)).isFalse();
        assertThat(WorkdayJobBoardClient.isRetryable(404)).isFalse();
    }

    @Test
    @DisplayName("requests are spaced across the process: each waits a further interval")
    void spacing() {
        WorkdayJobBoardClient client = new WorkdayJobBoardClient(WebClient.builder(), false, 200);
        long first = client.reserveSlot();
        long second = client.reserveSlot();
        long third = client.reserveSlot();
        assertThat(first).isLessThanOrEqualTo(5);
        assertThat(second).isBetween(150L, 250L);
        assertThat(third).isBetween(350L, 450L);
    }

    @Test
    @DisplayName("an interval of zero turns the spacing off")
    void off() {
        WorkdayJobBoardClient client = new WorkdayJobBoardClient(WebClient.builder(), false, 0);
        assertThat(client.reserveSlot()).isZero();
        assertThat(client.reserveSlot()).isZero();
    }
}
