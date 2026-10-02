package com.airral.service;

import com.airral.security.ApiKeyStore;
import com.airral.security.LoginThrottle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the scheduled sync's process ends, which is what colours the workflow run.
 *
 * <p>From 2026-09-30 every run logged "One-shot sync finished" and was then
 * cancelled at the 120-minute limit: {@code run} returned, but the task
 * scheduler's non-daemon thread kept the JVM up. The process has to end
 * itself, with 1 for a run the workflow should fail and 0 otherwise.
 */
class ExternalJobSyncRunnerTest {

    private final ExternalJobSyncService sync = mock(ExternalJobSyncService.class);
    private final ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
    private final LoginThrottle loginThrottle = mock(LoginThrottle.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final List<Integer> exits = new ArrayList<>();

    private final ExternalJobSyncRunner runner =
            new ExternalJobSyncRunner(sync, apiKeyStore, loginThrottle, 1, context, exits::add);

    private void syncEndsWith(String status) {
        when(sync.syncActiveSources()).thenReturn(Mono.just(new ExternalJobSyncResult(status, 3, 10, 10, 0, 0, 0)));
        when(apiKeyStore.purgeUsageBefore(any())).thenReturn(Mono.just(0L));
        when(loginThrottle.purgeBefore(any())).thenReturn(Mono.just(0L));
    }

    @ParameterizedTest(name = "{0} exits {1}")
    @CsvSource({
            "SUCCESS, 0",
            "PARTIAL_SUCCESS, 0",
            // Another run holds the lease: a normal no-op, not a red run.
            "SKIPPED_LOCKED, 0",
            "FAILED, 1",
            "DEGRADED, 1",
    })
    @DisplayName("the process exits with the run's result instead of outliving it")
    void exitsWithTheRunsResult(String status, int expectedExit) {
        syncEndsWith(status);

        runner.run(null);

        assertThat(exits).containsExactly(expectedExit);
        var order = inOrder(sync, context);
        order.verify(sync).syncActiveSources();
        order.verify(context).close();
    }

    @Test
    @DisplayName("a sync that errors exits 1, without the housekeeping")
    void syncThatErrorsExitsOne() {
        when(sync.syncActiveSources()).thenReturn(Mono.error(new IllegalStateException("database went away")));

        runner.run(null);

        assertThat(exits).containsExactly(1);
        verify(context).close();
        verify(apiKeyStore, never()).purgeUsageBefore(any());
        verify(loginThrottle, never()).purgeBefore(any());
    }

    @Test
    @DisplayName("a sync that returns nothing exits 1")
    void syncWithNoResultExitsOne() {
        when(sync.syncActiveSources()).thenReturn(Mono.empty());

        runner.run(null);

        assertThat(exits).containsExactly(1);
    }

    @Test
    @DisplayName("housekeeping that fails does not fail a completed sync")
    void housekeepingFailureStillExitsZero() {
        syncEndsWith("SUCCESS");
        when(apiKeyStore.purgeUsageBefore(any())).thenReturn(Mono.error(new IllegalStateException("boom")));
        when(loginThrottle.purgeBefore(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        runner.run(null);

        assertThat(exits).containsExactly(0);
    }
}
