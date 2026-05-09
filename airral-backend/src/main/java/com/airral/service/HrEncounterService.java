package com.airral.service;

import com.airral.domain.HrEncounter;
import com.airral.dto.request.CreateEncounterRequest;
import com.airral.dto.response.EncounterResponse;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.HrEncounterRepository;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class HrEncounterService {

    private final HrEncounterRepository encounterRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public HrEncounterService(HrEncounterRepository encounterRepository,
                             ApplicationRepository applicationRepository,
                             JobRepository jobRepository,
                             UserRepository userRepository) {
        this.encounterRepository = encounterRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    /**
     * Create a new encounter
     */
    public Mono<EncounterResponse> createEncounter(CreateEncounterRequest request, Long organizationId, Long userId) {
        HrEncounter encounter = HrEncounter.builder()
                .organizationId(organizationId)
                .encounterType(request.getEncounterType())
                .title(request.getTitle())
                .description(request.getDescription())
                .notes(request.getNotes())
                .applicationId(request.getApplicationId())
                .jobId(request.getJobId())
                .candidateId(request.getCandidateId())
                .performedById(userId)
                .interviewId(request.getInterviewId())
                .offerId(request.getOfferId())
                .outcome(request.getOutcome())
                .rating(request.getRating())
                .recommendation(request.getRecommendation())
                .priority(request.getPriority())
                .metadata(request.getMetadata())
                .encounteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        return encounterRepository.save(encounter)
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Get all encounters for organization
     */
    public Flux<EncounterResponse> getAllEncounters(Long organizationId) {
        return encounterRepository.findByOrganizationId(organizationId)
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Get encounters for an application (timeline)
     */
    public Flux<EncounterResponse> getEncountersByApplication(Long applicationId) {
        return encounterRepository.findByApplicationId(applicationId)
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Get encounters for a job
     */
    public Flux<EncounterResponse> getEncountersByJob(Long jobId) {
        return encounterRepository.findByJobId(jobId)
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Get recent encounters
     */
    public Flux<EncounterResponse> getRecentEncounters(Long organizationId, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        return encounterRepository.findRecentByOrganizationId(organizationId, since, limit)
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Get encounters by type
     */
    public Flux<EncounterResponse> getEncountersByType(Long organizationId, String encounterType) {
        return encounterRepository.findByOrganizationIdAndEncounterType(organizationId, encounterType)
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Get encounter by ID
     */
    public Mono<EncounterResponse> getEncounterById(Long id, Long organizationId) {
        return encounterRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Encounter not found")))
                .flatMap(this::toEncounterResponse);
    }

    /**
     * Convert HrEncounter to EncounterResponse DTO
     */
    private Mono<EncounterResponse> toEncounterResponse(HrEncounter encounter) {
        Mono<String> candidateNameMono = encounter.getApplicationId() != null ?
                applicationRepository.findById(encounter.getApplicationId())
                        .map(app -> app.getApplicantName())
                        .defaultIfEmpty("Unknown") :
                Mono.just(null);

        Mono<String> jobTitleMono = encounter.getJobId() != null ?
                jobRepository.findById(encounter.getJobId())
                        .map(job -> job.getTitle())
                        .defaultIfEmpty("Unknown") :
                Mono.just(null);

        Mono<String> performedByNameMono = encounter.getPerformedById() != null ?
                userRepository.findById(encounter.getPerformedById())
                        .map(user -> user.getFullName())
                        .defaultIfEmpty("System") :
                Mono.just("System");

        return Mono.zip(candidateNameMono, jobTitleMono, performedByNameMono)
                .map(tuple -> EncounterResponse.builder()
                        .id(encounter.getId())
                        .organizationId(encounter.getOrganizationId())
                        .encounterType(encounter.getEncounterType())
                        .title(encounter.getTitle())
                        .description(encounter.getDescription())
                        .notes(encounter.getNotes())
                        .applicationId(encounter.getApplicationId())
                        .candidateName(tuple.getT1())
                        .jobId(encounter.getJobId())
                        .jobTitle(tuple.getT2())
                        .candidateId(encounter.getCandidateId())
                        .performedById(encounter.getPerformedById())
                        .performedByName(tuple.getT3())
                        .interviewId(encounter.getInterviewId())
                        .offerId(encounter.getOfferId())
                        .outcome(encounter.getOutcome())
                        .rating(encounter.getRating())
                        .recommendation(encounter.getRecommendation())
                        .priority(encounter.getPriority())
                        .encounteredAt(encounter.getEncounteredAt())
                        .createdAt(encounter.getCreatedAt())
                        .metadata(encounter.getMetadata())
                        .build()
                );
    }
}
