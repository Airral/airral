package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.exception.NotFoundException;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

@Service
public class InternalJobCatalogProjectionService {

    static final String SOURCE_TYPE = "AIRRAL_INTERNAL";
    private static final String SOURCE_NAME = "AIRRAL employer";
    private static final int INTERNAL_JOB_RETENTION_DAYS = 36500;

    private final JobRepository jobRepository;
    private final OrganizationRepository organizationRepository;
    private final ExternalJobPostingStore externalJobPostingStore;

    public InternalJobCatalogProjectionService(
            JobRepository jobRepository,
            OrganizationRepository organizationRepository,
            ExternalJobPostingStore externalJobPostingStore) {
        this.jobRepository = jobRepository;
        this.organizationRepository = organizationRepository;
        this.externalJobPostingStore = externalJobPostingStore;
    }

    public Mono<Void> sync(Job job) {
        if (job == null || job.getId() == null) {
            return Mono.error(new IllegalArgumentException("A saved job is required for catalog projection"));
        }
        if (job.getStatus() != JobStatus.OPEN) {
            return externalJobPostingStore.deactivateInternalJob(job.getId()).then();
        }
        if (job.getOrganizationId() == null) {
            return Mono.error(new IllegalArgumentException("An organization is required for an open job"));
        }

        return organizationRepository.findById(job.getOrganizationId())
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found for job")))
                .flatMap(organization -> projectOpenJob(job, organization));
    }

    public Mono<Void> deactivate(Long jobId) {
        return externalJobPostingStore.deactivateInternalJob(jobId).then();
    }

    public Mono<Long> reconcileOpenJobs() {
        return externalJobPostingStore.deactivateStaleInternalJobs()
                .thenMany(jobRepository.findOpenJobs().concatMap(job -> sync(job).thenReturn(1L)))
                .reduce(0L, Long::sum);
    }

    private Mono<Void> projectOpenJob(Job job, Organization organization) {
        CandidateJobSummaryResponse summary = toSummary(job, organization);
        CandidateJobDetailResponse detail = toDetail(job, organization, summary);

        return externalJobPostingStore.ensureInternalSource(organization)
                .flatMap(source -> externalJobPostingStore.upsertJob(source, summary, INTERNAL_JOB_RETENTION_DAYS))
                .then(externalJobPostingStore.cacheJobDetail(detail))
                .then();
    }

    CandidateJobSummaryResponse toSummary(Job job, Organization organization) {
        OffsetDateTime updatedAt = toOffsetDateTime(firstNonNull(job.getUpdatedAt(), job.getCreatedAt()));
        String salaryLabel = salaryLabel(job);

        return CandidateJobSummaryResponse.builder()
                .jobId(sourceJobKey(job))
                .sourceType(SOURCE_TYPE)
                .sourceName(SOURCE_NAME)
                .sourceBoardToken(boardToken(job))
                .externalJobId(String.valueOf(job.getId()))
                .title(job.getTitle())
                .companyName(organization.getName())
                .companyDomain(organization.getDomain())
                .companyLogoUrl(organization.getLogoUrl())
                .department(job.getDepartment())
                .location(job.getLocation())
                .workMode(inferWorkMode(job.getLocation()))
                .employmentType(job.getEmploymentType())
                .salaryLabel(salaryLabel)
                .applyMode("INTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(updatedAt)
                .postedLabel("Posted by employer")
                .connectionsCount(0)
                .tags(jobTags(job))
                .jobQualityScore(96)
                .qualityReasons(qualityReasons(job, salaryLabel))
                .totalCompLabel(salaryLabel == null ? "Benchmark needed" : "Base listed")
                .compensationConfidence(salaryLabel == null ? "NEEDS_BENCHMARK" : "POSTED_BASE")
                .sponsorshipLanguage("UNKNOWN")
                .visaConfidenceScore(55)
                .visaReasons(List.of("Sponsorship not stated"))
                .build();
    }

    CandidateJobDetailResponse toDetail(
            Job job,
            Organization organization,
            CandidateJobSummaryResponse summary) {
        String description = fullDescription(job);

        return CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .externalInternalJobId(String.valueOf(job.getId()))
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .companyDomain(summary.getCompanyDomain())
                .companyLogoUrl(summary.getCompanyLogoUrl())
                .department(summary.getDepartment())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionText(description)
                .descriptionExcerpt(excerpt(description))
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .salaryCurrency(firstNonBlank(job.getCurrency(), "USD"))
                .salaryLabel(summary.getSalaryLabel())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(payloadHash(job))
                .jobQualityScore(summary.getJobQualityScore())
                .qualityReasons(summary.getQualityReasons())
                .totalCompLabel(summary.getTotalCompLabel())
                .compensationConfidence(summary.getCompensationConfidence())
                .sponsorshipLanguage(summary.getSponsorshipLanguage())
                .visaConfidenceScore(summary.getVisaConfidenceScore())
                .visaReasons(summary.getVisaReasons())
                .build();
    }

    private String boardToken(Job job) {
        return "organization-" + job.getOrganizationId();
    }

    private String sourceJobKey(Job job) {
        return SOURCE_TYPE.toLowerCase(Locale.US) + ":" + boardToken(job) + ":" + job.getId();
    }

    private String fullDescription(Job job) {
        StringJoiner sections = new StringJoiner("\n\n");
        addSection(sections, null, job.getDescription());
        addSection(sections, "Requirements", job.getRequirements());
        addSection(sections, "Nice to have", job.getNiceToHave());
        return sections.toString();
    }

    private void addSection(StringJoiner sections, String heading, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sections.add(heading == null ? value.trim() : heading + "\n" + value.trim());
    }

    private String excerpt(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalized = description.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 277) + "...";
    }

    private List<String> jobTags(Job job) {
        Set<String> tags = new LinkedHashSet<>();
        addTag(tags, job.getDepartment());
        addTag(tags, job.getEmploymentType());
        return List.copyOf(tags);
    }

    private void addTag(Set<String> tags, String value) {
        if (value != null && !value.isBlank()) {
            tags.add(value.trim());
        }
    }

    private List<String> qualityReasons(Job job, String salaryLabel) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Posted directly by employer");
        if (job.getDescription() != null && !job.getDescription().isBlank()) {
            reasons.add("Full description cached");
        }
        if (salaryLabel != null) {
            reasons.add("Employer salary listed");
        }
        if (job.getLocation() != null && !job.getLocation().isBlank()) {
            reasons.add("Location clear");
        }
        return reasons;
    }

    private String inferWorkMode(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String normalized = location.toLowerCase(Locale.US);
        if (normalized.contains("remote")) {
            return "REMOTE";
        }
        if (normalized.contains("hybrid")) {
            return "HYBRID";
        }
        return "ONSITE";
    }

    private String salaryLabel(Job job) {
        if (job.getSalaryMin() == null && job.getSalaryMax() == null) {
            return null;
        }
        String currency = firstNonBlank(job.getCurrency(), "USD");
        String prefix = "USD".equalsIgnoreCase(currency) ? "$" : currency.toUpperCase(Locale.US) + " ";
        if (job.getSalaryMin() != null && job.getSalaryMax() != null) {
            return prefix + formatAmount(job.getSalaryMin()) + " - " + prefix + formatAmount(job.getSalaryMax());
        }
        if (job.getSalaryMin() != null) {
            return "From " + prefix + formatAmount(job.getSalaryMin());
        }
        return "Up to " + prefix + formatAmount(job.getSalaryMax());
    }

    private String formatAmount(BigDecimal value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value.atOffset(ZoneOffset.UTC);
    }

    private String payloadHash(Job job) {
        String value = job.getId() + "|" + job.getUpdatedAt() + "|" + job.getTitle() + "|"
                + job.getDescription() + "|" + job.getRequirements() + "|" + job.getNiceToHave();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
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

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
