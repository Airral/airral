package com.airral.service;

import com.airral.domain.CandidateJobFitResult;
import com.airral.domain.CandidateResumeDocument;
import com.airral.domain.CandidateSavedJob;
import com.airral.domain.User;
import com.airral.dto.request.CandidateJobFitRequest;
import com.airral.dto.request.SaveCandidateJobRequest;
import com.airral.dto.request.UpdateCandidateSavedJobRequest;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobFitResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.dto.response.CandidateSavedJobResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.CandidateJobFitResultRepository;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.CandidateSavedJobRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class CandidateJobWorkspaceService {

    private final CandidateSavedJobRepository savedJobRepository;
    private final CandidateJobFitResultRepository fitResultRepository;
    private final CandidateResumeDocumentRepository resumeDocumentRepository;
    private final CandidateProfileRepository candidateProfileRepository;
    private final UserRepository userRepository;
    private final ExternalJobPostingStore externalJobPostingStore;
    private final CandidateJobSearchService candidateJobSearchService;
    private final ResumeJobFitAnalyzer resumeJobFitAnalyzer;
    private final ObjectMapper objectMapper;

    public CandidateJobWorkspaceService(
            CandidateSavedJobRepository savedJobRepository,
            CandidateJobFitResultRepository fitResultRepository,
            CandidateResumeDocumentRepository resumeDocumentRepository,
            CandidateProfileRepository candidateProfileRepository,
            UserRepository userRepository,
            ExternalJobPostingStore externalJobPostingStore,
            CandidateJobSearchService candidateJobSearchService,
            ResumeJobFitAnalyzer resumeJobFitAnalyzer,
            ObjectMapper objectMapper) {
        this.savedJobRepository = savedJobRepository;
        this.fitResultRepository = fitResultRepository;
        this.resumeDocumentRepository = resumeDocumentRepository;
        this.candidateProfileRepository = candidateProfileRepository;
        this.userRepository = userRepository;
        this.externalJobPostingStore = externalJobPostingStore;
        this.candidateJobSearchService = candidateJobSearchService;
        this.resumeJobFitAnalyzer = resumeJobFitAnalyzer;
        this.objectMapper = objectMapper;
    }

    public Flux<CandidateSavedJobResponse> listSavedJobs(String email) {
        return currentUser(email)
                .flatMapMany(user -> savedJobRepository.findByUserIdOrderByUpdatedAtDesc(user.getId())
                        .flatMap(savedJob -> toResponse(user, savedJob)));
    }

    public Mono<CandidateSavedJobResponse> saveJob(String email, SaveCandidateJobRequest request) {
        if (request == null || request.getSourceJobKey() == null || request.getSourceJobKey().isBlank()) {
            return Mono.error(new BadRequestException("sourceJobKey is required"));
        }

        String sourceJobKey = request.getSourceJobKey().trim();
        return currentUser(email)
                .flatMap(user -> externalJobPostingStore.findActiveJobSummary(sourceJobKey)
                        .switchIfEmpty(Mono.error(new NotFoundException("Active job not found")))
                        .then(savedJobRepository.findByUserIdAndSourceJobKey(user.getId(), sourceJobKey)
                                .defaultIfEmpty(newSavedJob(user.getId(), sourceJobKey))
                                .flatMap(savedJob -> {
                                    applySaveRequest(savedJob, request);
                                    savedJob.setUpdatedAt(OffsetDateTime.now());
                                    return savedJobRepository.save(savedJob);
                                })
                                .flatMap(savedJob -> toResponse(user, savedJob))));
    }

    public Mono<CandidateSavedJobResponse> updateSavedJob(String email, Long savedJobId, UpdateCandidateSavedJobRequest request) {
        return currentUser(email)
                .flatMap(user -> savedJobRepository.findByIdAndUserId(savedJobId, user.getId())
                        .switchIfEmpty(Mono.error(new NotFoundException("Saved job not found")))
                        .flatMap(savedJob -> {
                            if (request.getStatus() != null) savedJob.setStatus(normalizeStatus(request.getStatus()));
                            if (request.getResumeDocumentId() != null) savedJob.setResumeDocumentId(request.getResumeDocumentId());
                            if (request.getFitResultId() != null) savedJob.setFitResultId(request.getFitResultId());
                            if (request.getNextStep() != null) savedJob.setNextStep(request.getNextStep());
                            if (request.getNextStepDueAt() != null) savedJob.setNextStepDueAt(request.getNextStepDueAt());
                            if (request.getNotes() != null) savedJob.setNotes(request.getNotes());
                            savedJob.setUpdatedAt(OffsetDateTime.now());
                            return savedJobRepository.save(savedJob);
                        })
                        .flatMap(savedJob -> toResponse(user, savedJob)));
    }

    public Mono<Void> deleteSavedJob(String email, Long savedJobId) {
        return currentUser(email)
                .flatMap(user -> savedJobRepository.deleteByIdAndUserId(savedJobId, user.getId()));
    }

    public Mono<CandidateJobFitResponse> runFit(String email, CandidateJobFitRequest request) {
        if (request == null || request.getSourceJobKey() == null || request.getSourceJobKey().isBlank()) {
            return Mono.error(new BadRequestException("sourceJobKey is required"));
        }

        String sourceJobKey = request.getSourceJobKey().trim();
        return currentUser(email)
                .flatMap(user -> resolveResume(user, request.getResumeDocumentId())
                        .zipWith(resolveJobDetail(sourceJobKey))
                        .flatMap(tuple -> {
                            CandidateResumeDocument resume = tuple.getT1();
                            CandidateJobDetailResponse detail = tuple.getT2();
                            ResumeJobFitAnalyzer.FitAnalysis draft = resumeJobFitAnalyzer.analyze(resume, detail);
                            OffsetDateTime now = OffsetDateTime.now();

                            CandidateJobFitResult result = CandidateJobFitResult.builder()
                                    .userId(user.getId())
                                    .sourceJobKey(sourceJobKey)
                                    .resumeDocumentId(resume.getId())
                                    .fitScore(draft.fitScore())
                                    .visaFitScore(detail.getVisaConfidenceScore())
                                    .matchedRequirements(toJson(draft.matchedRequirements()))
                                    .missingRequirements(toJson(draft.missingRequirements()))
                                    .keywordGaps(toJson(draft.keywordGaps()))
                                    .weakBullets(toJson(draft.weakBullets()))
                                    .suggestedRewrites(toJson(draft.suggestedRewrites()))
                                    .applicationChecklist(toJson(draft.applicationChecklist()))
                                    .generatedAt(now)
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build();

                            return fitResultRepository.save(result)
                                    .flatMap(savedResult -> attachFitToSavedJob(user.getId(), sourceJobKey, resume.getId(), savedResult.getId())
                                            .thenReturn(toFitResponse(savedResult, detail)));
                        }));
    }

    private Mono<User> currentUser(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")));
    }

    private CandidateSavedJob newSavedJob(Long userId, String sourceJobKey) {
        OffsetDateTime now = OffsetDateTime.now();
        return CandidateSavedJob.builder()
                .userId(userId)
                .sourceJobKey(sourceJobKey)
                .status("SAVED")
                .nextStep("Check resume fit")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void applySaveRequest(CandidateSavedJob savedJob, SaveCandidateJobRequest request) {
        savedJob.setStatus(normalizeStatus(firstNonBlank(request.getStatus(), savedJob.getStatus(), "SAVED")));
        if (request.getResumeDocumentId() != null) savedJob.setResumeDocumentId(request.getResumeDocumentId());
        if (request.getNextStep() != null) savedJob.setNextStep(request.getNextStep());
        if (request.getNextStepDueAt() != null) savedJob.setNextStepDueAt(request.getNextStepDueAt());
        if (request.getNotes() != null) savedJob.setNotes(request.getNotes());
    }

    private Mono<Void> attachFitToSavedJob(Long userId, String sourceJobKey, Long resumeDocumentId, Long fitResultId) {
        return savedJobRepository.findByUserIdAndSourceJobKey(userId, sourceJobKey)
                .defaultIfEmpty(newSavedJob(userId, sourceJobKey))
                .flatMap(savedJob -> {
                    savedJob.setResumeDocumentId(resumeDocumentId);
                    savedJob.setFitResultId(fitResultId);
                    savedJob.setNextStep(firstNonBlank(savedJob.getNextStep(), "Apply or tailor resume"));
                    savedJob.setUpdatedAt(OffsetDateTime.now());
                    return savedJobRepository.save(savedJob);
                })
                .then();
    }

    private Mono<CandidateResumeDocument> resolveResume(User user, Long requestedResumeId) {
        if (requestedResumeId != null) {
            return resumeDocumentRepository.findByIdAndUserId(requestedResumeId, user.getId())
                    .switchIfEmpty(Mono.error(new NotFoundException("Resume not found")));
        }

        return candidateProfileRepository.findByUserId(user.getId())
                .flatMap(profile -> profile.getActiveResumeDocumentId() == null
                        ? Mono.<CandidateResumeDocument>empty()
                        : resumeDocumentRepository.findByIdAndUserId(profile.getActiveResumeDocumentId(), user.getId()))
                .switchIfEmpty(resumeDocumentRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).next())
                .switchIfEmpty(Mono.error(new BadRequestException("Upload a resume before running job fit")));
    }

    private Mono<CandidateJobDetailResponse> resolveJobDetail(String sourceJobKey) {
        String[] parts = sourceJobKey.split(":", 3);
        if (parts.length != 3) {
            return Mono.error(new BadRequestException("Invalid sourceJobKey"));
        }
        return candidateJobSearchService.getExternalJobDetail(parts[0], parts[1], parts[2]);
    }

    private Mono<CandidateSavedJobResponse> toResponse(User user, CandidateSavedJob savedJob) {
        Mono<CandidateJobSummaryResponse> jobMono = externalJobPostingStore.findActiveJobSummary(savedJob.getSourceJobKey())
                .defaultIfEmpty(CandidateJobSummaryResponse.builder()
                        .jobId(savedJob.getSourceJobKey())
                        .title("Saved job")
                        .companyName("Source unavailable")
                        .sourceType("UNKNOWN")
                        .sponsorshipLanguage("UNKNOWN")
                        .build());
        Mono<CandidateJobFitResponse> fitMono = savedJob.getFitResultId() == null
                ? Mono.empty()
                : fitResultRepository.findByIdAndUserId(savedJob.getFitResultId(), user.getId())
                        .map(result -> toFitResponse(result, null));

        return jobMono
                .flatMap(job -> fitMono.defaultIfEmpty(CandidateJobFitResponse.builder().build())
                .map(fit -> CandidateSavedJobResponse.builder()
                        .id(savedJob.getId())
                        .sourceJobKey(savedJob.getSourceJobKey())
                        .status(savedJob.getStatus())
                        .resumeDocumentId(savedJob.getResumeDocumentId())
                        .fitResultId(savedJob.getFitResultId())
                        .nextStep(savedJob.getNextStep())
                        .nextStepDueAt(savedJob.getNextStepDueAt())
                        .notes(savedJob.getNotes())
                        .job(job)
                        .fitResult(fit.getId() == null ? null : fit)
                        .createdAt(savedJob.getCreatedAt())
                        .updatedAt(savedJob.getUpdatedAt())
                        .build()));
    }

    private CandidateJobFitResponse toFitResponse(CandidateJobFitResult result, CandidateJobDetailResponse detail) {
        return CandidateJobFitResponse.builder()
                .id(result.getId())
                .sourceJobKey(result.getSourceJobKey())
                .resumeDocumentId(result.getResumeDocumentId())
                .fitScore(result.getFitScore())
                .visaFitScore(result.getVisaFitScore())
                .matchedRequirements(fromJsonList(result.getMatchedRequirements()))
                .missingRequirements(fromJsonList(result.getMissingRequirements()))
                .keywordGaps(fromJsonList(result.getKeywordGaps()))
                .weakBullets(fromJsonList(result.getWeakBullets()))
                .suggestedRewrites(fromJsonList(result.getSuggestedRewrites()))
                .applicationChecklist(fromJsonList(result.getApplicationChecklist()))
                .job(detail)
                .generatedAt(result.getGeneratedAt())
                .build();
    }

    private Json toJson(Object value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return Json.of("[]");
        }
    }

    private List<String> fromJsonList(Json json) {
        if (json == null || json.asString() == null || json.asString().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json.asString(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "SAVED";
        }
        String normalized = status.trim().toUpperCase(Locale.US);
        return switch (normalized) {
            case "SAVED", "APPLYING", "APPLIED", "INTERVIEWING", "OFFER", "REJECTED", "ARCHIVED" -> normalized;
            default -> "SAVED";
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

}
