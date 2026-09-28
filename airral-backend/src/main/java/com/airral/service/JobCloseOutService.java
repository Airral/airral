package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CloseOutRequest;
import com.airral.dto.response.CloseOutResponse;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Wrapping up a job once someone is hired: mark it filled, and turn down the
 * candidates still in progress, telling them when HR asks.
 *
 * <p>A candidate with an offer out is left alone: turning them down would
 * contradict the offer, so HR withdraws it first.
 */
@Service
public class JobCloseOutService {

    /** Stages a candidate is still being considered in. */
    static final Set<ApplicationStatus> IN_PROGRESS = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW, ApplicationStatus.SHORTLISTED,
            ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEWED);

    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;
    private final JobService jobService;
    private final CandidateUpdateEmails candidateEmails;

    public JobCloseOutService(JobRepository jobRepository,
                              ApplicationRepository applicationRepository,
                              JobService jobService,
                              CandidateUpdateEmails candidateEmails) {
        this.jobRepository = jobRepository;
        this.applicationRepository = applicationRepository;
        this.jobService = jobService;
        this.candidateEmails = candidateEmails;
    }

    public Mono<CloseOutResponse> closeOut(Long jobId, Long organizationId, JobScope scope, CloseOutRequest request) {
        boolean markFilled = Boolean.TRUE.equals(request.getMarkFilled());
        boolean turnDown = Boolean.TRUE.equals(request.getTurnDownOthers());
        boolean notify = Boolean.TRUE.equals(request.getNotifyCandidates());

        return jobRepository.findByIdAndOrganizationId(jobId, organizationId)
                .filter(job -> scope.allows(job.getId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(job -> applicationRepository.findByJobIdAndOrganizationId(jobId, organizationId)
                        .collectList()
                        .flatMap(applications -> {
                            int withOpenOffers = (int) applications.stream()
                                    .filter(application -> application.getStatus() == ApplicationStatus.OFFER_EXTENDED)
                                    .count();
                            List<Application> toTurnDown = turnDown
                                    ? applications.stream().filter(application -> IN_PROGRESS.contains(application.getStatus())).toList()
                                    : List.of();

                            Mono<Long> turnedDown = Flux.fromIterable(toTurnDown)
                                    .concatMap(application -> {
                                        application.setStatus(ApplicationStatus.REJECTED);
                                        application.setUpdatedAt(LocalDateTime.now());
                                        return applicationRepository.save(application);
                                    })
                                    .doOnNext(saved -> {
                                        if (notify) candidateEmails.notSelected(saved);
                                    })
                                    .count();
                            Mono<Boolean> filled = markFilled && job.getStatus() != JobStatus.FILLED
                                    ? jobService.updateJobStatus(jobId, JobStatus.FILLED, organizationId).thenReturn(true)
                                    : Mono.just(false);

                            return turnedDown.zipWith(filled, (count, wasFilled) -> CloseOutResponse.builder()
                                    .markedFilled(wasFilled)
                                    .turnedDown(count.intValue())
                                    .withOpenOffers(withOpenOffers)
                                    .build());
                        }));
    }
}
