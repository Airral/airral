package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.StaticApplicationContext;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The re-parse writes to every candidate's profile, so writing has to be asked for. */
class ResumeReparseRunnerTest {

    private final ResumeReparseService service = mock(ResumeReparseService.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=" + ResumeReparseRunner.PROFILE)
            .withBean(ResumeReparseService.class, () -> service)
            .withUserConfiguration(ResumeReparseRunner.class);

    @Test
    @DisplayName("the profile alone is a dry run")
    void dryRunByDefault() {
        when(service.reparse(anyBoolean())).thenReturn(Mono.just(ResumeReparseService.Report.empty(true)));

        contextRunner.run(context -> {
            assertThat(context.getBean(ResumeReparseRunner.class).reparse()).isZero();
            verify(service).reparse(true);
        });
    }

    @Test
    @DisplayName("airral.resume-reparse.apply=true writes")
    void applyWrites() {
        when(service.reparse(anyBoolean())).thenReturn(Mono.just(ResumeReparseService.Report.empty(false)));

        contextRunner.withPropertyValues("airral.resume-reparse.apply=true").run(context -> {
            context.getBean(ResumeReparseRunner.class).reparse();
            verify(service).reparse(false);
        });
    }

    @Test
    @DisplayName("a failed run exits non-zero")
    void failureExitsNonZero() {
        when(service.reparse(anyBoolean())).thenReturn(Mono.error(new IllegalStateException("database unreachable")));

        assertThat(new ResumeReparseRunner(service, new StaticApplicationContext(), true, 1).reparse()).isEqualTo(1);
    }
}
