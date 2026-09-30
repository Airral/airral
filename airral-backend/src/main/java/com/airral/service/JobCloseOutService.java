package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CloseOutRequest;
import com.airral.dto.response.CloseOutResponse;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wrapping up a job once someone is hired: mark it filled, and turn down the
 * candidates still in progress, telling them when HR asks.
 *
 * <p>A candidate with an offer still being made (a draft, or one sent and
 * neither answered nor lapsed) is left alone: turning them down would
 * contradict the offer, so HR withdraws it first. One whose offer lapsed is
 * turned down like the rest.
 */
@Service
public class JobCloseOutService {

    /**
     * Stages a candidate is still being considered in. The offer stage is one
     * of them: a candidate there whose offer is still open is skipped, and one
     * whose offer lapsed is turned down.
     */
    static final Set<ApplicationStatus> TURNED_DOWN_AT_CLOSE_OUT = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW, ApplicationStatus.SHORTLISTED,
            ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEWED, ApplicationStatus.OFFER_EXTENDED);

    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;
    private final JobService jobService;
    private final CandidateUpdateEmails candidateEmails;
    private final OfferRepository offerRepository;

    public JobCloseOutService(JobRepository jobRepository,
                              ApplicationRepository applicationRepository,
                              JobService jobService,
                              CandidateUpdateEmails candidateEmails,
                              OfferRepository offerRepository) {
        this.jobRepository = jobRepository;
        this.applicationRepository = applicationRepository;
        this.jobService = jobService;
        this.candidateEmails = candidateEmails;
        this.offerRepository = offerRepository;
    }

    @Transactional
    public Mono<CloseOutResponse> closeOut(Long jobId, Long organizationId, JobScope scope, CloseOutRequest request) {
        boolean markFilled = Boolean.TRUE.equals(request.getMarkFilled());
        boolean turnDown = Boolean.TRUE.equals(request.getTurnDownOthers());
        boolean notify = Boolean.TRUE.equals(request.getNotifyCandidates());
        LocalDateTime now = LocalDateTime.now();

        return jobRepository.findByIdAndOrganizationId(jobId, organizationId)
                .filter(job -> scope.allows(job.getId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                // One step at a time: everything here shares the transaction's connection.
                .flatMap(job -> applicationRepository.findByJobIdAndOrganizationId(jobId, organizationId).collectList()
                        .zipWhen(applications -> offerRepository.findApplicationIdsWithOpenOffers(jobId, now)
                                .collect(Collectors.toSet()))
                        .flatMap(found -> {
                            List<Application> applications = found.getT1();
                            Set<Long> withOffers = found.getT2();
                            int withOpenOffers = (int) applications.stream()
                                    .filter(application -> withOffers.contains(application.getId()))
                                    .count();
                            List<Application> toTurnDown = turnDown
                                    ? applications.stream()
                                            .filter(application -> TURNED_DOWN_AT_CLOSE_OUT.contains(application.getStatus()))
                                            .filter(application -> !withOffers.contains(application.getId()))
                                            .toList()
                                    : List.of();

                            Mono<Long> turnedDown = Flux.fromIterable(toTurnDown)
                                    .concatMap(application -> {
                                        application.setStatus(ApplicationStatus.REJECTED);
                                        application.setUpdatedAt(now);
                                        // Any offer left is one that lapsed: recorded as expired.
                                        return offerRepository.closeOpen(application.getId(), now)
                                                .then(applicationRepository.save(application));
                                    })
                                    .concatMap(saved -> notify
                                            ? AfterCommit.run(() -> candidateEmails.notSelected(saved)).thenReturn(saved)
                                            : Mono.just(saved))
                                    .count();
                            Mono<Boolean> filled = Mono.defer(() -> markFilled && job.getStatus() != JobStatus.FILLED
                                    ? jobService.updateJobStatus(jobId, JobStatus.FILLED, organizationId).thenReturn(true)
                                    : Mono.just(false));

                            return turnedDown.flatMap(count -> filled.map(wasFilled -> CloseOutResponse.builder()
                                    .markedFilled(wasFilled)
                                    .turnedDown(count.intValue())
                                    .withOpenOffers(withOpenOffers)
                                    .build()));
                        }));
    }
}
