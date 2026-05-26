package com.airral.service;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

@Service
public class ExternalJobPostingStore {

    private final DatabaseClient databaseClient;
    private final CompanyLogoService companyLogoService;

    public ExternalJobPostingStore(DatabaseClient databaseClient, CompanyLogoService companyLogoService) {
        this.databaseClient = databaseClient;
        this.companyLogoService = companyLogoService;
    }

    public Flux<ExternalJobSourceRecord> findActiveSources() {
        return databaseClient.sql("""
                        SELECT
                            s.id,
                            s.company_id,
                            c.name AS company_name,
                            c.domain AS company_domain,
                            s.source_type,
                            s.board_token,
                            s.source_name
                        FROM external_job_sources s
                        JOIN external_companies c ON c.id = s.company_id
                        WHERE s.is_active = true
                          AND c.is_active = true
                        ORDER BY s.last_success_at ASC NULLS FIRST, c.name ASC
                        """)
                .map((row, metadata) -> new ExternalJobSourceRecord(
                        row.get("id", Long.class),
                        row.get("company_id", Long.class),
                        row.get("company_name", String.class),
                        row.get("company_domain", String.class),
                        row.get("source_type", String.class),
                        row.get("board_token", String.class),
                        row.get("source_name", String.class)
                ))
                .all();
    }

    public Flux<CandidateJobSummaryResponse> findRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company) {
        return findRecommendedJobs(source, boardToken, limit, 0, maxAgeDays, query, company);
    }

    public Flux<CandidateJobSummaryResponse> findRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer offset,
            Integer maxAgeDays,
            String query,
            String company) {
        int resolvedLimit = normalizeLimit(limit);
        int resolvedOffset = normalizeOffset(offset);
        String normalizedSource = normalizeSource(source);

        StringBuilder sql = new StringBuilder("""
                SELECT
                    p.source_job_key,
                    p.source_type,
                    p.source_name,
                    p.source_board_token,
                    p.external_job_id,
                    p.title,
                    c.name AS company_name,
                    c.domain AS company_domain,
                    c.logo_url AS company_logo_url,
                    p.department,
                    p.location,
                    p.work_mode,
                    p.employment_type,
                    p.salary_label,
                    p.apply_url,
                    p.job_url,
                    p.apply_mode,
                    p.easy_apply_available,
                    p.source_updated_at,
                    p.posted_label,
                    p.match_score,
                    p.connections_count,
                    p.tags,
                    COALESCE(p.job_quality_score, p.match_score, 78) AS job_quality_score,
                    p.quality_reasons,
                    COALESCE(
                        p.total_comp_label,
                        CASE
                            WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' THEN 'Benchmark needed'
                            ELSE 'Base listed'
                        END
                    ) AS total_comp_label,
                    COALESCE(
                        p.compensation_confidence,
                        CASE
                            WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' THEN 'NEEDS_BENCHMARK'
                            ELSE 'POSTED_BASE'
                        END
                    ) AS compensation_confidence
                FROM external_job_postings p
                JOIN external_companies c ON c.id = p.company_id
                WHERE p.is_active = true
                  AND p.expires_at > CURRENT_TIMESTAMP
                """);

        if (!"ALL".equals(normalizedSource)) {
            sql.append(" AND p.source_type = :sourceType");
        }
        if (boardToken != null && !boardToken.isBlank()) {
            sql.append(" AND p.source_board_token = :boardToken");
        }
        if (maxAgeDays != null && maxAgeDays > 0) {
            sql.append(" AND p.source_updated_at >= :sourceCutoff");
        }
        if (company != null && !company.isBlank()) {
            sql.append(" AND LOWER(c.name) LIKE :company");
        }
        if (query != null && !query.isBlank()) {
            sql.append("""
                     AND LOWER(CONCAT_WS(' ',
                        p.title,
                        c.name,
                        p.department,
                        p.location,
                        p.work_mode,
                        p.employment_type,
                        p.salary_label,
                        p.source_name
                    )) LIKE :query
                    """);
        }

        sql.append(" ORDER BY p.source_updated_at DESC NULLS LAST, p.match_score DESC NULLS LAST, p.last_seen_at DESC LIMIT :limit OFFSET :offset");

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("limit", resolvedLimit)
                .bind("offset", resolvedOffset);

        if (!"ALL".equals(normalizedSource)) {
            spec = spec.bind("sourceType", normalizedSource);
        }
        if (boardToken != null && !boardToken.isBlank()) {
            spec = spec.bind("boardToken", boardToken.trim());
        }
        if (maxAgeDays != null && maxAgeDays > 0) {
            spec = spec.bind("sourceCutoff", OffsetDateTime.now(ZoneOffset.UTC).minusDays(maxAgeDays));
        }
        if (company != null && !company.isBlank()) {
            spec = spec.bind("company", like(company));
        }
        if (query != null && !query.isBlank()) {
            spec = spec.bind("query", like(query));
        }

        return spec.map((row, metadata) -> withStoreFallbacks(CandidateJobSummaryResponse.builder()
                        .jobId(row.get("source_job_key", String.class))
                        .sourceType(row.get("source_type", String.class))
                        .sourceName(row.get("source_name", String.class))
                        .sourceBoardToken(row.get("source_board_token", String.class))
                        .externalJobId(row.get("external_job_id", String.class))
                        .title(row.get("title", String.class))
                        .companyName(row.get("company_name", String.class))
                        .companyDomain(companyLogoService.normalizeDomain(row.get("company_domain", String.class)))
                        .companyLogoUrl(companyLogoService.logoUrl(
                                row.get("company_domain", String.class),
                                row.get("company_logo_url", String.class)))
                        .department(row.get("department", String.class))
                        .location(row.get("location", String.class))
                        .workMode(row.get("work_mode", String.class))
                        .employmentType(row.get("employment_type", String.class))
                        .salaryLabel(row.get("salary_label", String.class))
                        .applyUrl(row.get("apply_url", String.class))
                        .jobUrl(row.get("job_url", String.class))
                        .applyMode(row.get("apply_mode", String.class))
                        .easyApplyAvailable(row.get("easy_apply_available", Boolean.class))
                        .sourceUpdatedAt(row.get("source_updated_at", OffsetDateTime.class))
                        .postedLabel(row.get("posted_label", String.class))
                        .matchScore(row.get("match_score", Integer.class))
                        .connectionsCount(row.get("connections_count", Integer.class))
                        .tags(tagsFrom(row.get("tags", Object.class)))
                        .jobQualityScore(row.get("job_quality_score", Integer.class))
                        .qualityReasons(tagsFrom(row.get("quality_reasons", Object.class)))
                        .totalCompLabel(row.get("total_comp_label", String.class))
                        .compensationConfidence(row.get("compensation_confidence", String.class))
                        .build()))
                .all();
    }

    public Mono<Long> upsertJob(ExternalJobSourceRecord source, CandidateJobSummaryResponse job, int retentionDays) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime sourceUpdatedAt = job.getSourceUpdatedAt();
        OffsetDateTime expiresAt = (sourceUpdatedAt == null ? now : sourceUpdatedAt)
                .plusDays(Math.max(1, retentionDays));

        String sourceType = normalizeSource(firstNonBlank(job.getSourceType(), source.sourceType()));
        String sourceBoardToken = firstNonBlank(job.getSourceBoardToken(), source.boardToken());
        String externalJobId = firstNonBlank(job.getExternalJobId(), job.getJobId(), sourceType + ":" + sourceBoardToken + ":" + job.getTitle());
        String sourceJobKey = sourceJobKey(sourceType, sourceBoardToken, externalJobId);
        List<String> tags = job.getTags() == null ? List.of() : job.getTags();

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO external_job_postings (
                            company_id,
                            job_source_id,
                            source_type,
                            source_name,
                            source_board_token,
                            external_job_id,
                            source_job_key,
                            title,
                            department,
                            location,
                            work_mode,
                            employment_type,
                            salary_label,
                            apply_url,
                            job_url,
                            apply_mode,
                            easy_apply_available,
                            source_updated_at,
                            posted_label,
                            match_score,
                            connections_count,
                            tags,
                            job_quality_score,
                            quality_reasons,
                            total_comp_label,
                            compensation_confidence,
                            source_payload_hash,
                            is_active,
                            last_seen_at,
                            expires_at,
                            deleted_at,
                            updated_at
                        )
                        VALUES (
                            :companyId,
                            :jobSourceId,
                            :sourceType,
                            :sourceName,
                            :sourceBoardToken,
                            :externalJobId,
                            :sourceJobKey,
                            :title,
                            :department,
                            :location,
                            :workMode,
                            :employmentType,
                            :salaryLabel,
                            :applyUrl,
                            :jobUrl,
                            :applyMode,
                            :easyApplyAvailable,
                            :sourceUpdatedAt,
                            :postedLabel,
                            :matchScore,
                            :connectionsCount,
                            :tags,
                            :jobQualityScore,
                            :qualityReasons,
                            :totalCompLabel,
                            :compensationConfidence,
                            :sourcePayloadHash,
                            true,
                            :now,
                            :expiresAt,
                            NULL,
                            :now
                        )
                        ON CONFLICT (source_type, source_board_token, external_job_id)
                        DO UPDATE SET
                            company_id = EXCLUDED.company_id,
                            job_source_id = EXCLUDED.job_source_id,
                            source_name = EXCLUDED.source_name,
                            title = EXCLUDED.title,
                            department = EXCLUDED.department,
                            location = EXCLUDED.location,
                            work_mode = EXCLUDED.work_mode,
                            employment_type = EXCLUDED.employment_type,
                            salary_label = EXCLUDED.salary_label,
                            apply_url = EXCLUDED.apply_url,
                            job_url = EXCLUDED.job_url,
                            apply_mode = EXCLUDED.apply_mode,
                            easy_apply_available = EXCLUDED.easy_apply_available,
                            source_updated_at = EXCLUDED.source_updated_at,
                            posted_label = EXCLUDED.posted_label,
                            match_score = EXCLUDED.match_score,
                            connections_count = EXCLUDED.connections_count,
                            tags = EXCLUDED.tags,
                            job_quality_score = EXCLUDED.job_quality_score,
                            quality_reasons = EXCLUDED.quality_reasons,
                            total_comp_label = EXCLUDED.total_comp_label,
                            compensation_confidence = EXCLUDED.compensation_confidence,
                            source_payload_hash = EXCLUDED.source_payload_hash,
                            is_active = true,
                            last_seen_at = EXCLUDED.last_seen_at,
                            expires_at = EXCLUDED.expires_at,
                            deleted_at = NULL,
                            updated_at = EXCLUDED.updated_at
                        """)
                .bind("companyId", source.companyId())
                .bind("jobSourceId", source.id())
                .bind("sourceType", sourceType)
                .bind("sourceName", firstNonBlank(source.sourceName(), job.getSourceName(), sourceType))
                .bind("sourceBoardToken", sourceBoardToken)
                .bind("externalJobId", externalJobId)
                .bind("sourceJobKey", sourceJobKey)
                .bind("title", firstNonBlank(job.getTitle(), "Untitled role"))
                .bind("applyMode", firstNonBlank(job.getApplyMode(), "EXTERNAL_APPLY"))
                .bind("easyApplyAvailable", Boolean.TRUE.equals(job.getEasyApplyAvailable()))
                .bind("connectionsCount", job.getConnectionsCount() == null ? 0 : job.getConnectionsCount())
                .bind("tags", tags.toArray(String[]::new))
                .bind("qualityReasons", qualityReasonsFor(job).toArray(String[]::new))
                .bind("sourcePayloadHash", payloadHash(source, job))
                .bind("now", now)
                .bind("expiresAt", expiresAt);

        spec = bindNullable(spec, "department", job.getDepartment(), String.class);
        spec = bindNullable(spec, "location", job.getLocation(), String.class);
        spec = bindNullable(spec, "workMode", job.getWorkMode(), String.class);
        spec = bindNullable(spec, "employmentType", job.getEmploymentType(), String.class);
        spec = bindNullable(spec, "salaryLabel", job.getSalaryLabel(), String.class);
        spec = bindNullable(spec, "applyUrl", job.getApplyUrl(), String.class);
        spec = bindNullable(spec, "jobUrl", job.getJobUrl(), String.class);
        spec = bindNullable(spec, "sourceUpdatedAt", sourceUpdatedAt, OffsetDateTime.class);
        spec = bindNullable(spec, "postedLabel", job.getPostedLabel(), String.class);
        spec = bindNullable(spec, "matchScore", job.getMatchScore(), Integer.class);
        spec = bindNullable(spec, "jobQualityScore", firstNonNull(job.getJobQualityScore(), job.getMatchScore()), Integer.class);
        spec = bindNullable(spec, "totalCompLabel", firstNonBlank(job.getTotalCompLabel(), inferTotalCompLabel(job.getSalaryLabel())), String.class);
        spec = bindNullable(spec, "compensationConfidence", firstNonBlank(job.getCompensationConfidence(), inferCompensationConfidence(job.getSalaryLabel())), String.class);

        return spec.fetch().rowsUpdated();
    }

    public Mono<CandidateJobDetailResponse> findCachedJobDetail(String sourceType, String boardToken, String externalJobId) {
        if (sourceType == null || sourceType.isBlank()
                || boardToken == null || boardToken.isBlank()
                || externalJobId == null || externalJobId.isBlank()) {
            return Mono.empty();
        }

        return databaseClient.sql("""
                        SELECT
                            p.source_job_key,
                            p.source_type,
                            p.source_name,
                            p.source_board_token,
                            p.external_job_id,
                            p.external_internal_job_id,
                            p.title,
                            c.name AS company_name,
                            c.domain AS company_domain,
                            c.logo_url AS company_logo_url,
                            p.department,
                            p.location,
                            p.work_mode,
                            p.employment_type,
                            p.description_html,
                            p.description_text,
                            p.description_excerpt,
                            p.salary_min,
                            p.salary_max,
                            p.salary_currency,
                            p.salary_label,
                            p.apply_url,
                            p.job_url,
                            p.apply_mode,
                            p.source_updated_at,
                            p.posted_label,
                            p.match_score,
                            p.connections_count,
                            p.tags,
                            p.source_payload_hash,
                            COALESCE(p.job_quality_score, p.match_score, 78) AS job_quality_score,
                            p.quality_reasons,
                            COALESCE(
                                p.total_comp_label,
                                CASE
                                    WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' THEN 'Benchmark needed'
                                    ELSE 'Base listed'
                                END
                            ) AS total_comp_label,
                            COALESCE(
                                p.compensation_confidence,
                                CASE
                                    WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' THEN 'NEEDS_BENCHMARK'
                                    ELSE 'POSTED_BASE'
                                END
                            ) AS compensation_confidence
                        FROM external_job_postings p
                        JOIN external_companies c ON c.id = p.company_id
                        WHERE p.source_type = :sourceType
                          AND p.source_board_token = :boardToken
                          AND p.external_job_id = :externalJobId
                          AND p.is_active = true
                          AND p.expires_at > CURRENT_TIMESTAMP
                          AND (
                              NULLIF(p.description_text, '') IS NOT NULL
                              OR NULLIF(p.description_html, '') IS NOT NULL
                          )
                        LIMIT 1
                        """)
                .bind("sourceType", normalizeSource(sourceType))
                .bind("boardToken", boardToken.trim())
                .bind("externalJobId", externalJobId.trim())
                .map((row, metadata) -> withStoreFallbacks(CandidateJobDetailResponse.builder()
                        .jobId(row.get("source_job_key", String.class))
                        .sourceType(row.get("source_type", String.class))
                        .sourceName(row.get("source_name", String.class))
                        .sourceBoardToken(row.get("source_board_token", String.class))
                        .externalJobId(row.get("external_job_id", String.class))
                        .externalInternalJobId(row.get("external_internal_job_id", String.class))
                        .title(row.get("title", String.class))
                        .companyName(row.get("company_name", String.class))
                        .companyDomain(companyLogoService.normalizeDomain(row.get("company_domain", String.class)))
                        .companyLogoUrl(companyLogoService.logoUrl(
                                row.get("company_domain", String.class),
                                row.get("company_logo_url", String.class)))
                        .department(row.get("department", String.class))
                        .location(row.get("location", String.class))
                        .workMode(row.get("work_mode", String.class))
                        .employmentType(row.get("employment_type", String.class))
                        .descriptionHtml(row.get("description_html", String.class))
                        .descriptionText(row.get("description_text", String.class))
                        .descriptionExcerpt(row.get("description_excerpt", String.class))
                        .salaryMin(row.get("salary_min", BigDecimal.class))
                        .salaryMax(row.get("salary_max", BigDecimal.class))
                        .salaryCurrency(row.get("salary_currency", String.class))
                        .salaryLabel(row.get("salary_label", String.class))
                        .applyUrl(row.get("apply_url", String.class))
                        .jobUrl(row.get("job_url", String.class))
                        .applyMode(row.get("apply_mode", String.class))
                        .sourceUpdatedAt(row.get("source_updated_at", OffsetDateTime.class))
                        .postedLabel(row.get("posted_label", String.class))
                        .matchScore(row.get("match_score", Integer.class))
                        .connectionsCount(row.get("connections_count", Integer.class))
                        .tags(tagsFrom(row.get("tags", Object.class)))
                        .sourcePayloadHash(row.get("source_payload_hash", String.class))
                        .jobQualityScore(row.get("job_quality_score", Integer.class))
                        .qualityReasons(tagsFrom(row.get("quality_reasons", Object.class)))
                        .totalCompLabel(row.get("total_comp_label", String.class))
                        .compensationConfidence(row.get("compensation_confidence", String.class))
                        .build()))
                .one();
    }

    public Mono<Boolean> existsActiveJob(String sourceType, String boardToken, String externalJobId) {
        if (sourceType == null || sourceType.isBlank()
                || boardToken == null || boardToken.isBlank()
                || externalJobId == null || externalJobId.isBlank()) {
            return Mono.just(false);
        }

        return databaseClient.sql("""
                        SELECT 1
                        FROM external_job_postings
                        WHERE source_type = :sourceType
                          AND source_board_token = :boardToken
                          AND external_job_id = :externalJobId
                          AND is_active = true
                          AND expires_at > CURRENT_TIMESTAMP
                        LIMIT 1
                        """)
                .bind("sourceType", normalizeSource(sourceType))
                .bind("boardToken", boardToken.trim())
                .bind("externalJobId", externalJobId.trim())
                .map((row, metadata) -> true)
                .one()
                .defaultIfEmpty(false);
    }

    public Mono<CandidateJobDetailResponse> attachCompanyBrand(CandidateJobDetailResponse detail) {
        if (detail == null
                || detail.getSourceType() == null || detail.getSourceType().isBlank()
                || detail.getSourceBoardToken() == null || detail.getSourceBoardToken().isBlank()
                || detail.getExternalJobId() == null || detail.getExternalJobId().isBlank()) {
            return Mono.just(detail);
        }

        return databaseClient.sql("""
                        SELECT
                            c.name AS company_name,
                            c.domain AS company_domain,
                            c.logo_url AS company_logo_url
                        FROM external_job_postings p
                        JOIN external_companies c ON c.id = p.company_id
                        WHERE p.source_type = :sourceType
                          AND p.source_board_token = :boardToken
                          AND p.external_job_id = :externalJobId
                          AND p.is_active = true
                          AND p.expires_at > CURRENT_TIMESTAMP
                        LIMIT 1
                        """)
                .bind("sourceType", normalizeSource(detail.getSourceType()))
                .bind("boardToken", detail.getSourceBoardToken().trim())
                .bind("externalJobId", detail.getExternalJobId().trim())
                .map((row, metadata) -> {
                    detail.setCompanyName(firstNonBlank(row.get("company_name", String.class), detail.getCompanyName()));
                    detail.setCompanyDomain(companyLogoService.normalizeDomain(row.get("company_domain", String.class)));
                    detail.setCompanyLogoUrl(companyLogoService.logoUrl(
                            row.get("company_domain", String.class),
                            row.get("company_logo_url", String.class)));
                    return detail;
                })
                .one()
                .defaultIfEmpty(detail);
    }

    public Mono<Long> cacheJobDetail(CandidateJobDetailResponse detail) {
        if (detail == null
                || detail.getSourceType() == null || detail.getSourceType().isBlank()
                || detail.getSourceBoardToken() == null || detail.getSourceBoardToken().isBlank()
                || detail.getExternalJobId() == null || detail.getExternalJobId().isBlank()) {
            return Mono.just(0L);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE external_job_postings
                        SET external_internal_job_id = :externalInternalJobId,
                            description_html = :descriptionHtml,
                            description_text = :descriptionText,
                            description_excerpt = :descriptionExcerpt,
                            salary_min = :salaryMin,
                            salary_max = :salaryMax,
                            salary_currency = :salaryCurrency,
                            salary_label = :salaryLabel,
                            source_payload_hash = :sourcePayloadHash,
                            job_quality_score = :jobQualityScore,
                            quality_reasons = :qualityReasons,
                            total_comp_label = :totalCompLabel,
                            compensation_confidence = :compensationConfidence,
                            updated_at = :now
                        WHERE source_type = :sourceType
                          AND source_board_token = :sourceBoardToken
                          AND external_job_id = :externalJobId
                        """)
                .bind("sourceType", normalizeSource(detail.getSourceType()))
                .bind("sourceBoardToken", detail.getSourceBoardToken().trim())
                .bind("externalJobId", detail.getExternalJobId().trim())
                .bind("qualityReasons", qualityReasonsFor(detail).toArray(String[]::new))
                .bind("now", now);

        spec = bindNullable(spec, "externalInternalJobId", detail.getExternalInternalJobId(), String.class);
        spec = bindNullable(spec, "descriptionHtml", detail.getDescriptionHtml(), String.class);
        spec = bindNullable(spec, "descriptionText", detail.getDescriptionText(), String.class);
        spec = bindNullable(spec, "descriptionExcerpt", detail.getDescriptionExcerpt(), String.class);
        spec = bindNullable(spec, "salaryMin", detail.getSalaryMin(), BigDecimal.class);
        spec = bindNullable(spec, "salaryMax", detail.getSalaryMax(), BigDecimal.class);
        spec = bindNullable(spec, "salaryCurrency", detail.getSalaryCurrency(), String.class);
        spec = bindNullable(spec, "salaryLabel", detail.getSalaryLabel(), String.class);
        spec = bindNullable(spec, "sourcePayloadHash", detail.getSourcePayloadHash(), String.class);
        spec = bindNullable(spec, "jobQualityScore", firstNonNull(detail.getJobQualityScore(), detail.getMatchScore()), Integer.class);
        spec = bindNullable(spec, "totalCompLabel", firstNonBlank(detail.getTotalCompLabel(), inferTotalCompLabel(detail.getSalaryLabel())), String.class);
        spec = bindNullable(spec, "compensationConfidence", firstNonBlank(detail.getCompensationConfidence(), inferCompensationConfidence(detail.getSalaryLabel())), String.class);

        return spec.fetch().rowsUpdated();
    }

    public Mono<Long> expireOldJobs(int retentionDays) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(Math.max(1, retentionDays));
        return databaseClient.sql("""
                        UPDATE external_job_postings
                        SET is_active = false,
                            deleted_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE is_active = true
                          AND (
                              expires_at <= CURRENT_TIMESTAMP
                              OR source_updated_at IS NULL
                              OR source_updated_at < :cutoff
                          )
                        """)
                .bind("cutoff", cutoff)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> createSyncRun() {
        return databaseClient.sql("""
                        INSERT INTO external_job_sync_runs (status)
                        VALUES ('RUNNING')
                        RETURNING id
                        """)
                .map((row, metadata) -> row.get("id", Long.class))
                .one();
    }

    public Mono<Boolean> acquireSyncLease(String lockName, String lockedBy, Duration leaseDuration) {
        OffsetDateTime lockedUntil = OffsetDateTime.now(ZoneOffset.UTC).plus(leaseDuration);
        return databaseClient.sql("""
                        INSERT INTO external_job_sync_locks (
                            lock_name,
                            locked_by,
                            locked_until,
                            updated_at
                        )
                        VALUES (
                            :lockName,
                            :lockedBy,
                            :lockedUntil,
                            CURRENT_TIMESTAMP
                        )
                        ON CONFLICT (lock_name)
                        DO UPDATE SET
                            locked_by = EXCLUDED.locked_by,
                            locked_until = EXCLUDED.locked_until,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE external_job_sync_locks.locked_until <= CURRENT_TIMESTAMP
                           OR external_job_sync_locks.locked_by = EXCLUDED.locked_by
                        RETURNING lock_name
                        """)
                .bind("lockName", lockName)
                .bind("lockedBy", lockedBy)
                .bind("lockedUntil", lockedUntil)
                .map((row, metadata) -> true)
                .one()
                .defaultIfEmpty(false);
    }

    public Mono<Long> releaseSyncLease(String lockName, String lockedBy) {
        return databaseClient.sql("""
                        UPDATE external_job_sync_locks
                        SET locked_until = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE lock_name = :lockName
                          AND locked_by = :lockedBy
                        """)
                .bind("lockName", lockName)
                .bind("lockedBy", lockedBy)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> completeSyncRun(
            Long runId,
            String status,
            int sourcesCount,
            int jobsSeen,
            int jobsUpserted,
            long jobsExpired,
            String errorMessage) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE external_job_sync_runs
                        SET finished_at = CURRENT_TIMESTAMP,
                            status = :status,
                            sources_count = :sourcesCount,
                            jobs_seen = :jobsSeen,
                            jobs_upserted = :jobsUpserted,
                            jobs_expired = :jobsExpired,
                            error_message = :errorMessage
                        WHERE id = :runId
                        """)
                .bind("runId", runId)
                .bind("status", status)
                .bind("sourcesCount", sourcesCount)
                .bind("jobsSeen", jobsSeen)
                .bind("jobsUpserted", jobsUpserted)
                .bind("jobsExpired", Math.toIntExact(Math.min(Integer.MAX_VALUE, jobsExpired)));
        spec = bindNullable(spec, "errorMessage", errorMessage, String.class);
        return spec.fetch().rowsUpdated();
    }

    public Mono<Long> markSourceSuccess(Long sourceId) {
        return databaseClient.sql("""
                        UPDATE external_job_sources
                        SET last_synced_at = CURRENT_TIMESTAMP,
                            last_success_at = CURRENT_TIMESTAMP,
                            last_error = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :sourceId
                        """)
                .bind("sourceId", sourceId)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> markSourceError(Long sourceId, String errorMessage) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE external_job_sources
                        SET last_synced_at = CURRENT_TIMESTAMP,
                            last_error = :lastError,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :sourceId
                        """)
                .bind("sourceId", sourceId);
        spec = bindNullable(spec, "lastError", truncate(errorMessage, 2000), String.class);
        return spec.fetch().rowsUpdated();
    }

    private CandidateJobSummaryResponse withStoreFallbacks(CandidateJobSummaryResponse job) {
        if (job.getQualityReasons() == null || job.getQualityReasons().isEmpty()) {
            job.setQualityReasons(qualityReasonsFor(job));
        }
        if (job.getTotalCompLabel() == null || job.getTotalCompLabel().isBlank()) {
            job.setTotalCompLabel(inferTotalCompLabel(job.getSalaryLabel()));
        }
        if (job.getCompensationConfidence() == null || job.getCompensationConfidence().isBlank()) {
            job.setCompensationConfidence(inferCompensationConfidence(job.getSalaryLabel()));
        }
        if (job.getJobQualityScore() == null) {
            job.setJobQualityScore(firstNonNull(job.getMatchScore(), 78));
        }
        return job;
    }

    private CandidateJobDetailResponse withStoreFallbacks(CandidateJobDetailResponse detail) {
        if (detail.getQualityReasons() == null || detail.getQualityReasons().isEmpty()) {
            detail.setQualityReasons(qualityReasonsFor(detail));
        }
        if (detail.getTotalCompLabel() == null || detail.getTotalCompLabel().isBlank()) {
            detail.setTotalCompLabel(inferTotalCompLabel(detail.getSalaryLabel()));
        }
        if (detail.getCompensationConfidence() == null || detail.getCompensationConfidence().isBlank()) {
            detail.setCompensationConfidence(inferCompensationConfidence(detail.getSalaryLabel()));
        }
        if (detail.getJobQualityScore() == null) {
            detail.setJobQualityScore(firstNonNull(detail.getMatchScore(), 78));
        }
        return detail;
    }

    private DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec,
            String key,
            Object value,
            Class<?> valueType) {
        if (value == null) {
            return spec.bindNull(key, valueType);
        }
        return spec.bind(key, value);
    }

    private List<String> qualityReasonsFor(CandidateJobSummaryResponse job) {
        if (job.getQualityReasons() != null && !job.getQualityReasons().isEmpty()) {
            return job.getQualityReasons();
        }

        return qualityReasonsFor(
                job.getSalaryLabel(),
                job.getLocation(),
                job.getSourceUpdatedAt(),
                job.getApplyUrl(),
                job.getJobUrl(),
                job.getDepartment(),
                null);
    }

    private List<String> qualityReasonsFor(CandidateJobDetailResponse detail) {
        if (detail.getQualityReasons() != null && !detail.getQualityReasons().isEmpty()) {
            return detail.getQualityReasons();
        }

        return qualityReasonsFor(
                detail.getSalaryLabel(),
                detail.getLocation(),
                detail.getSourceUpdatedAt(),
                detail.getApplyUrl(),
                detail.getJobUrl(),
                detail.getDepartment(),
                detail.getDescriptionText());
    }

    private List<String> qualityReasonsFor(
            String salaryLabel,
            String location,
            OffsetDateTime sourceUpdatedAt,
            String applyUrl,
            String jobUrl,
            String department,
            String descriptionText) {
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add(isSalaryMissing(salaryLabel) ? "Needs salary benchmark" : "Employer salary listed");
        if (location != null && !location.isBlank() && !location.equalsIgnoreCase("Location not listed")) {
            reasons.add("Location clear");
        }
        if (sourceUpdatedAt != null) {
            reasons.add("Fresh source date");
        }
        if (firstNonBlank(applyUrl, jobUrl) != null) {
            reasons.add("Direct apply link");
        }
        if (department != null && !department.isBlank()) {
            reasons.add("Team listed");
        }
        if (descriptionText != null && descriptionText.length() > 300) {
            reasons.add("Full description cached");
        }
        return reasons.stream().limit(5).toList();
    }

    private String inferTotalCompLabel(String salaryLabel) {
        if (isSalaryMissing(salaryLabel)) {
            return "Benchmark needed";
        }

        String normalized = salaryLabel.toLowerCase(Locale.US);
        if (normalized.contains("equity") || normalized.contains("stock") || normalized.contains("bonus")) {
            return "Base + extras listed";
        }

        return "Base listed";
    }

    private String inferCompensationConfidence(String salaryLabel) {
        return isSalaryMissing(salaryLabel) ? "NEEDS_BENCHMARK" : "POSTED_BASE";
    }

    private boolean isSalaryMissing(String salaryLabel) {
        return salaryLabel == null
                || salaryLabel.isBlank()
                || salaryLabel.toLowerCase(Locale.US).contains("not listed");
    }

    private List<String> tagsFrom(Object rawValue) {
        if (rawValue instanceof String[] tags) {
            return Arrays.stream(tags).filter(Objects::nonNull).toList();
        }
        if (rawValue instanceof List<?> tags) {
            return tags.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, 500));
    }

    private int normalizeOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        return Math.max(0, offset);
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "ALL";
        }
        return source.trim().replace("-", "_").toUpperCase(Locale.US);
    }

    private String like(String value) {
        return "%" + value.trim().toLowerCase(Locale.US) + "%";
    }

    private String sourceJobKey(String sourceType, String boardToken, String externalJobId) {
        return sourceType.toLowerCase(Locale.US) + ":" + boardToken + ":" + externalJobId;
    }

    private String payloadHash(ExternalJobSourceRecord source, CandidateJobSummaryResponse job) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(source.sourceType());
        joiner.add(source.boardToken());
        joiner.add(String.valueOf(job.getExternalJobId()));
        joiner.add(String.valueOf(job.getTitle()));
        joiner.add(String.valueOf(job.getLocation()));
        joiner.add(String.valueOf(job.getSalaryLabel()));
        joiner.add(String.valueOf(job.getApplyUrl()));
        joiner.add(String.valueOf(job.getSourceUpdatedAt()));
        return hash(joiner.toString());
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
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
    private <T> T firstNonNull(T... values) {
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
