package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CloseOutRequest;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobCloseOutTest {

    private static final long ACME = 1L;
    private static final long JOB = 10L;

    private final JobRepository jobs = mock(JobRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final CandidateUpdateEmails emails = mock(CandidateUpdateEmails.class);
    private final JobCloseOutService service = new JobCloseOutService(jobs, applications, jobService, emails);

    private final Job job = Job.builder().id(JOB).organizationId(ACME).title("Store manager").status(JobStatus.OPEN).build();
    private List<Application> pipeline;

    @BeforeEach
    void setUp() {
        pipeline = List.of(
                candidate(1, ApplicationStatus.HIRED),
                candidate(2, ApplicationStatus.SUBMITTED),
                candidate(3, ApplicationStatus.SHORTLISTED),
                candidate(4, ApplicationStatus.INTERVIEWED),
                candidate(5, ApplicationStatus.OFFER_EXTENDED),
                candidate(6, ApplicationStatus.REJECTED),
                candidate(7, ApplicationStatus.WITHDRAWN));
        when(jobs.findByIdAndOrganizationId(JOB, ACME)).thenReturn(Mono.just(job));
        when(applications.findByJobIdAndOrganizationId(JOB, ACME)).thenReturn(Flux.fromIterable(pipeline));
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jobService.updateJobStatus(JOB, JobStatus.FILLED, ACME)).thenReturn(Mono.empty());
    }

    private static Application candidate(long id, ApplicationStatus status) {
        return Application.builder().id(id).jobId(JOB).applicantEmail("c" + id + "@example.com").status(status).build();
    }

    private Map<Long, ApplicationStatus> statuses() {
        return pipeline.stream().collect(Collectors.toMap(Application::getId, Application::getStatus));
    }

    @Test
    @DisplayName("turning the others down leaves the hire, the finished, and anyone with an offer out")
    void turnDownInProgressOnly() {
        StepVerifier.create(service.closeOut(JOB, ACME, JobScope.wholeCompany(),
                        CloseOutRequest.builder().markFilled(true).turnDownOthers(true).notifyCandidates(true).build()))
                .assertNext(result -> {
                    assertThat(result.getTurnedDown()).isEqualTo(3);
                    assertThat(result.getWithOpenOffers()).isEqualTo(1);
                    assertThat(result.isMarkedFilled()).isTrue();
                })
                .verifyComplete();

        assertThat(statuses()).containsEntry(1L, ApplicationStatus.HIRED)
                .containsEntry(2L, ApplicationStatus.REJECTED)
                .containsEntry(3L, ApplicationStatus.REJECTED)
                .containsEntry(4L, ApplicationStatus.REJECTED)
                .containsEntry(5L, ApplicationStatus.OFFER_EXTENDED)
                .containsEntry(6L, ApplicationStatus.REJECTED)
                .containsEntry(7L, ApplicationStatus.WITHDRAWN);
        verify(emails, times(3)).notSelected(any());
        verify(jobService).updateJobStatus(JOB, JobStatus.FILLED, ACME);
    }

    @Test
    @DisplayName("candidates are emailed only when HR asks, and the job is marked filled only when asked")
    void eachStepIsOptional() {
        StepVerifier.create(service.closeOut(JOB, ACME, JobScope.wholeCompany(),
                        CloseOutRequest.builder().turnDownOthers(true).build()))
                .assertNext(result -> {
                    assertThat(result.getTurnedDown()).isEqualTo(3);
                    assertThat(result.isMarkedFilled()).isFalse();
                })
                .verifyComplete();
        verify(emails, never()).notSelected(any());
        verify(jobService, never()).updateJobStatus(any(), any(), any());
    }

    @Test
    @DisplayName("a job already filled is not filled again")
    void alreadyFilled() {
        job.setStatus(JobStatus.FILLED);

        StepVerifier.create(service.closeOut(JOB, ACME, JobScope.wholeCompany(),
                        CloseOutRequest.builder().markFilled(true).build()))
                .assertNext(result -> assertThat(result.isMarkedFilled()).isFalse())
                .verifyComplete();
        verify(jobService, never()).updateJobStatus(any(), any(), any());
    }

    @Test
    @DisplayName("a hiring manager closes out only their own jobs")
    void managerScope() {
        StepVerifier.create(service.closeOut(JOB, ACME, JobScope.only(Set.of(99L)),
                        CloseOutRequest.builder().markFilled(true).turnDownOthers(true).build()))
                .expectError(NotFoundException.class)
                .verify();
        verify(applications, never()).save(any());
        verify(jobService, never()).updateJobStatus(any(), any(), any());
    }
}
