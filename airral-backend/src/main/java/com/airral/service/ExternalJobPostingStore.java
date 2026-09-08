package com.airral.service;

import com.airral.domain.Organization;
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
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

@Service
public class ExternalJobPostingStore {

    private static final int MAX_RECOMMENDED_QUERY_LIMIT = 2000;

    private final DatabaseClient databaseClient;
    private final CompanyLogoService companyLogoService;

    /**
     * Declared width of every VARCHAR this class writes, keyed by bind name.
     *
     * <p>None of these were capped. A value longer than its column throws inside
     * the sync's {@code flatMap(..., 8)}, which cancels the rest of that board --
     * so one over-long field silently cost every remaining posting from that
     * employer, with no error to say the run was partial. The documented case is
     * an Ashby compensation summary, which is vendor free text going into
     * {@code salary_label VARCHAR(255)}, but every entry here is reachable from
     * source data: Lever's commitment is free text, Workday's external path can
     * be long once URL-encoded, and multi-site postings concatenate locations.
     *
     * <p>Truncating loses the tail of one field. Throwing loses the board. Keep
     * this in step with the migrations; a name absent here is simply not capped.
     */
    private static final Map<String, Integer> TEXT_COLUMN_LIMITS = Map.ofEntries(
            Map.entry("salaryCurrency", 10),
            Map.entry("seniorityLabel", 20),
            Map.entry("compensationConfidence", 30),
            Map.entry("sourceType", 30),
            Map.entry("workMode", 30),
            Map.entry("applyMode", 40),
            Map.entry("sponsorshipLanguage", 40),
            Map.entry("employmentType", 80),
            Map.entry("postedLabel", 80),
            Map.entry("sourceName", 100),
            Map.entry("sourcePayloadHash", 128),
            Map.entry("department", 255),
            Map.entry("externalInternalJobId", 255),
            Map.entry("salaryLabel", 255),
            Map.entry("sourceBoardToken", 255),
            Map.entry("totalCompLabel", 255),
            Map.entry("externalJobId", 500),
            Map.entry("location", 500),
            Map.entry("title", 500),
            Map.entry("sourceJobKey", 900));

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
                          AND s.source_type <> 'AIRRAL_INTERNAL'
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

    public Mono<ExternalJobSourceRecord> ensureInternalSource(Organization organization) {
        if (organization == null || organization.getId() == null) {
            return Mono.error(new IllegalArgumentException("An organization is required for an internal job source"));
        }

        String boardToken = "organization-" + organization.getId();
        String companyKey = "airral-internal-org-" + organization.getId();
        String companyName = firstNonBlank(organization.getName(), "AIRRAL employer");

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        WITH upserted_company AS (
                            INSERT INTO external_companies (
                                name,
                                normalized_name,
                                domain,
                                logo_url,
                                verification_status,
                                is_active,
                                updated_at
                            )
                            VALUES (
                                :companyName,
                                :companyKey,
                                :companyDomain,
                                :companyLogoUrl,
                                'VERIFIED',
                                true,
                                CURRENT_TIMESTAMP
                            )
                            ON CONFLICT (normalized_name)
                            DO UPDATE SET
                                name = EXCLUDED.name,
                                domain = EXCLUDED.domain,
                                logo_url = EXCLUDED.logo_url,
                                verification_status = 'VERIFIED',
                                is_active = true,
                                updated_at = CURRENT_TIMESTAMP
                            RETURNING id
                        ), upserted_source AS (
                            INSERT INTO external_job_sources (
                                company_id,
                                source_type,
                                board_token,
                                source_name,
                                is_active,
                                updated_at
                            )
                            SELECT
                                id,
                                'AIRRAL_INTERNAL',
                                :boardToken,
                                'AIRRAL employer',
                                true,
                                CURRENT_TIMESTAMP
                            FROM upserted_company
                            ON CONFLICT (source_type, board_token)
                            DO UPDATE SET
                                company_id = EXCLUDED.company_id,
                                source_name = EXCLUDED.source_name,
                                is_active = true,
                                last_error = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            RETURNING id, company_id
                        )
                        SELECT id, company_id
                        FROM upserted_source
                        """)
                .bind("companyName", companyName)
                .bind("companyKey", companyKey)
                .bind("boardToken", boardToken);
        spec = bindNullable(spec, "companyDomain", organization.getDomain(), String.class);
        spec = bindNullable(spec, "companyLogoUrl", organization.getLogoUrl(), String.class);

        return spec.map((row, metadata) -> new ExternalJobSourceRecord(
                        row.get("id", Long.class),
                        row.get("company_id", Long.class),
                        companyName,
                        organization.getDomain(),
                        "AIRRAL_INTERNAL",
                        boardToken,
                        "AIRRAL employer"))
                .one();
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
        return findRecommendedJobs(source, boardToken, limit, offset, maxAgeDays, query, company,
                ExplicitJobFilters.none());
    }

    public Flux<CandidateJobSummaryResponse> findRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer offset,
            Integer maxAgeDays,
            String query,
            String company,
            ExplicitJobFilters filters) {
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
                    ) AS compensation_confidence,
                    p.sponsorship_language,
                    p.visa_confidence_score,
                    p.visa_reasons,
                    p.requires_us_work_authorization,
                    p.contract_or_staffing_risk,
                    p.stem_opt_risk,
                    p.h1b_transfer_fit,
                    p.cap_exempt_fit,
                    p.experience_years,
                    p.seniority_label
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
                     AND (
                        p.search_vector @@ plainto_tsquery('english', :query)
                        OR LOWER(c.name) LIKE :queryLike
                        OR LOWER(p.title) LIKE :queryLike
                     )
                    """);
        }

        appendExplicitFilters(sql, filters);

        sql.append(" ORDER BY p.source_updated_at DESC NULLS LAST, p.match_score DESC NULLS LAST, p.last_seen_at DESC LIMIT :limit OFFSET :offset");

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("limit", resolvedLimit)
                .bind("offset", resolvedOffset);

        if (!"ALL".equals(normalizedSource)) {
            spec = spec.bind("sourceType", normalizedSource);
        }
        if (filters != null && filters.hasWorkMode()
                && !"REMOTE".equals(filters.normalizedWorkMode())) {
            spec = spec.bind("filterWorkMode", filters.normalizedWorkMode());
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
            spec = spec.bind("query", query.trim());
            spec = spec.bind("queryLike", like(query));
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
                        .sponsorshipLanguage(row.get("sponsorship_language", String.class))
                        .visaConfidenceScore(row.get("visa_confidence_score", Integer.class))
                        .visaReasons(tagsFrom(row.get("visa_reasons", Object.class)))
                        .requiresUsWorkAuthorization(row.get("requires_us_work_authorization", Boolean.class))
                        .contractOrStaffingRisk(row.get("contract_or_staffing_risk", Boolean.class))
                        .stemOptRisk(row.get("stem_opt_risk", Boolean.class))
                        .h1bTransferFit(row.get("h1b_transfer_fit", Boolean.class))
                        .capExemptFit(row.get("cap_exempt_fit", Boolean.class))
                        .seniorityLabel(row.get("seniority_label", String.class))
                        .experienceYears(row.get("experience_years", Integer.class))
                        .build()))
                .all();
    }

    public Mono<Long> countActivePostings() {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS total
                        FROM external_job_postings p
                        JOIN external_companies c ON c.id = p.company_id
                        JOIN external_job_sources s ON s.id = p.job_source_id
                        WHERE p.is_active = true
                          AND p.expires_at > CURRENT_TIMESTAMP
                          AND p.source_type <> 'AIRRAL_INTERNAL'
                          AND c.is_active = true
                          AND s.is_active = true
                        """)
                .map((row, metadata) -> row.get("total", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    public Mono<Long> countCompaniesWithActivePostings() {
        return databaseClient.sql("""
                        SELECT COUNT(DISTINCT p.company_id) AS total
                        FROM external_job_postings p
                        JOIN external_companies c ON c.id = p.company_id
                        JOIN external_job_sources s ON s.id = p.job_source_id
                        WHERE p.is_active = true
                          AND p.expires_at > CURRENT_TIMESTAMP
                          AND p.source_type <> 'AIRRAL_INTERNAL'
                          AND c.is_active = true
                          AND s.is_active = true
                        """)
                .map((row, metadata) -> row.get("total", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * Find active jobs whose tags or full-text search vector match any of the given skills.
     * Used for skill-based retrieval: after parsing a resume, we search the DB for jobs
     * that mention the candidate's skills in tags, title, department, or description.
     */
    public Flux<CandidateJobSummaryResponse> findJobsBySkills(
            List<String> skills,
            int maxAgeDays,
            int limit) {
        if (skills == null || skills.isEmpty()) {
            return Flux.empty();
        }

        // Build a tsquery from the candidate's top skills (OR-combined)
        // e.g., "python | kafka | snowflake | react"
        String tsQuery = skills.stream()
                .filter(s -> s != null && !s.isBlank())
                .limit(12)
                .map(s -> s.toLowerCase(Locale.US)
                        .replaceAll("[^a-z0-9+#.]+", " ")
                        .strip()
                        .replace(" ", " & "))
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " | " + b)
                .orElse(null);

        if (tsQuery == null || tsQuery.isBlank()) {
            return Flux.empty();
        }

        int resolvedLimit = Math.max(1, Math.min(limit, MAX_RECOMMENDED_QUERY_LIMIT));

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
                    ) AS compensation_confidence,
                    p.sponsorship_language,
                    p.visa_confidence_score,
                    p.visa_reasons,
                    p.requires_us_work_authorization,
                    p.contract_or_staffing_risk,
                    p.stem_opt_risk,
                    p.h1b_transfer_fit,
                    p.cap_exempt_fit,
                    p.experience_years,
                    p.seniority_label,
                    ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) AS relevance
                FROM external_job_postings p
                JOIN external_companies c ON c.id = p.company_id
                WHERE p.is_active = true
                  AND p.expires_at > CURRENT_TIMESTAMP
                  AND p.search_vector @@ to_tsquery('english', :tsQuery)
                """);

        if (maxAgeDays > 0) {
            sql.append(" AND p.source_updated_at >= :sourceCutoff");
        }

        sql.append(" ORDER BY relevance DESC, p.source_updated_at DESC NULLS LAST LIMIT :limit");

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("tsQuery", tsQuery)
                .bind("limit", resolvedLimit);

        if (maxAgeDays > 0) {
            spec = spec.bind("sourceCutoff", OffsetDateTime.now(ZoneOffset.UTC).minusDays(maxAgeDays));
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
                        .sponsorshipLanguage(row.get("sponsorship_language", String.class))
                        .visaConfidenceScore(row.get("visa_confidence_score", Integer.class))
                        .visaReasons(tagsFrom(row.get("visa_reasons", Object.class)))
                        .requiresUsWorkAuthorization(row.get("requires_us_work_authorization", Boolean.class))
                        .contractOrStaffingRisk(row.get("contract_or_staffing_risk", Boolean.class))
                        .stemOptRisk(row.get("stem_opt_risk", Boolean.class))
                        .h1bTransferFit(row.get("h1b_transfer_fit", Boolean.class))
                        .capExemptFit(row.get("cap_exempt_fit", Boolean.class))
                        .seniorityLabel(row.get("seniority_label", String.class))
                        .experienceYears(row.get("experience_years", Integer.class))
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
                            description_text,
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
                            sponsorship_language,
                            visa_confidence_score,
                            visa_reasons,
                            requires_us_work_authorization,
                            contract_or_staffing_risk,
                            stem_opt_risk,
                            h1b_transfer_fit,
                            cap_exempt_fit,
                            experience_years,
                            seniority_label,
                            source_payload_hash,
                            is_active,
                            last_seen_at,
                            expires_at,
                            deleted_at,
                            updated_at,
                            search_vector
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
                            :descriptionText,
                            :salaryLabel,
                            :applyUrl,
                            :jobUrl,
                            :applyMode,
                            :easyApplyAvailable,
                            :sourceUpdatedAt,
                            :postedLabel,
                            :matchScore,
                            :connectionsCount,
                            CAST(:tags AS TEXT[]),
                            :jobQualityScore,
                            CAST(:qualityReasons AS TEXT[]),
                            :totalCompLabel,
                            :compensationConfidence,
                            :sponsorshipLanguage,
                            :visaConfidenceScore,
                            CAST(:visaReasons AS TEXT[]),
                            :requiresUsWorkAuthorization,
                            :contractOrStaffingRisk,
                            :stemOptRisk,
                            :h1bTransferFit,
                            :capExemptFit,
                            CAST(:experienceYears AS SMALLINT),
                            :seniorityLabel,
                            :sourcePayloadHash,
                            true,
                            :now,
                            :expiresAt,
                            NULL,
                            :now,
                            to_tsvector('english', CONCAT_WS(' ', :title, :department, :location, :employmentType, :sourceName, :tagsText, LEFT(:descriptionText, 2000)))
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
                            -- Never lose a stored body to a run that arrived without one.
                            description_text = COALESCE(NULLIF(EXCLUDED.description_text, ''), external_job_postings.description_text),

                            -- Pay is read from structured source fields, so the guard here is the
                            -- placeholder itself: a mapper with no pay data emits "Salary not listed",
                            -- and that must not overwrite a range already resolved for this posting.
                            salary_label = CASE
                                WHEN EXCLUDED.salary_label IS NULL
                                  OR LOWER(EXCLUDED.salary_label) LIKE '%not listed%'
                                THEN COALESCE(external_job_postings.salary_label, EXCLUDED.salary_label)
                                ELSE EXCLUDED.salary_label
                            END,
                            total_comp_label = CASE
                                WHEN EXCLUDED.total_comp_label IS NULL
                                  OR EXCLUDED.total_comp_label = 'Benchmark needed'
                                THEN COALESCE(external_job_postings.total_comp_label, EXCLUDED.total_comp_label)
                                ELSE EXCLUDED.total_comp_label
                            END,
                            compensation_confidence = CASE
                                WHEN EXCLUDED.compensation_confidence IS NULL
                                  OR EXCLUDED.compensation_confidence = 'NEEDS_BENCHMARK'
                                THEN COALESCE(external_job_postings.compensation_confidence, EXCLUDED.compensation_confidence)
                                ELSE EXCLUDED.compensation_confidence
                            END,

                            apply_url = EXCLUDED.apply_url,
                            job_url = EXCLUDED.job_url,
                            apply_mode = EXCLUDED.apply_mode,
                            easy_apply_available = EXCLUDED.easy_apply_available,
                            source_updated_at = EXCLUDED.source_updated_at,
                            posted_label = EXCLUDED.posted_label,
                            match_score = EXCLUDED.match_score,
                            connections_count = EXCLUDED.connections_count,
                            tags = EXCLUDED.tags,

                            -- Everything from here down is derived from the posting body. Take the
                            -- incoming value only when this run actually had a body to read;
                            -- otherwise keep what is stored, which may have been derived by a detail
                            -- view that did. Without this the sync overwrote real, description-derived
                            -- values with defaults every four hours, and no read path recomputed --
                            -- so the correct value survived exactly one sync interval.
                            job_quality_score = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.job_quality_score
                                ELSE COALESCE(external_job_postings.job_quality_score, EXCLUDED.job_quality_score)
                            END,
                            quality_reasons = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.quality_reasons
                                WHEN COALESCE(array_length(external_job_postings.quality_reasons, 1), 0) > 0
                                    THEN external_job_postings.quality_reasons
                                ELSE EXCLUDED.quality_reasons
                            END,
                            sponsorship_language = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.sponsorship_language
                                ELSE external_job_postings.sponsorship_language
                            END,
                            visa_confidence_score = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.visa_confidence_score
                                ELSE COALESCE(external_job_postings.visa_confidence_score, EXCLUDED.visa_confidence_score)
                            END,
                            visa_reasons = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.visa_reasons
                                WHEN COALESCE(array_length(external_job_postings.visa_reasons, 1), 0) > 0
                                    THEN external_job_postings.visa_reasons
                                ELSE EXCLUDED.visa_reasons
                            END,
                            requires_us_work_authorization = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.requires_us_work_authorization
                                ELSE COALESCE(external_job_postings.requires_us_work_authorization, EXCLUDED.requires_us_work_authorization)
                            END,
                            contract_or_staffing_risk = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.contract_or_staffing_risk
                                ELSE COALESCE(external_job_postings.contract_or_staffing_risk, EXCLUDED.contract_or_staffing_risk)
                            END,
                            stem_opt_risk = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.stem_opt_risk
                                ELSE COALESCE(external_job_postings.stem_opt_risk, EXCLUDED.stem_opt_risk)
                            END,
                            h1b_transfer_fit = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.h1b_transfer_fit
                                ELSE COALESCE(external_job_postings.h1b_transfer_fit, EXCLUDED.h1b_transfer_fit)
                            END,
                            cap_exempt_fit = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.cap_exempt_fit
                                ELSE COALESCE(external_job_postings.cap_exempt_fit, EXCLUDED.cap_exempt_fit)
                            END,
                            experience_years = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.experience_years
                                ELSE COALESCE(external_job_postings.experience_years, EXCLUDED.experience_years)
                            END,
                            seniority_label = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL THEN EXCLUDED.seniority_label
                                ELSE COALESCE(external_job_postings.seniority_label, EXCLUDED.seniority_label)
                            END,

                            source_payload_hash = EXCLUDED.source_payload_hash,
                            is_active = true,
                            last_seen_at = EXCLUDED.last_seen_at,
                            expires_at = EXCLUDED.expires_at,
                            deleted_at = NULL,
                            updated_at = EXCLUDED.updated_at,
                            -- Includes the body, so V21's enrichment survives a re-sync. The 2000
                            -- character cap matches the migration that introduced it.
                            search_vector = to_tsvector('english', CONCAT_WS(' ', EXCLUDED.title, EXCLUDED.department, EXCLUDED.location, EXCLUDED.employment_type, EXCLUDED.source_name, array_to_string(EXCLUDED.tags, ' '), LEFT(COALESCE(NULLIF(EXCLUDED.description_text, ''), external_job_postings.description_text), 2000)))
                        """)
                .bind("companyId", source.companyId())
                .bind("jobSourceId", source.id())
                .bind("sourceType", cappedText("sourceType", sourceType))
                .bind("sourceName", cappedText("sourceName",
                        firstNonBlank(source.sourceName(), job.getSourceName(), sourceType)))
                .bind("sourceBoardToken", cappedText("sourceBoardToken", sourceBoardToken))
                .bind("externalJobId", cappedText("externalJobId", externalJobId))
                .bind("sourceJobKey", cappedText("sourceJobKey", sourceJobKey))
                .bind("title", cappedText("title", firstNonBlank(job.getTitle(), "Untitled role")))
                .bind("applyMode", cappedText("applyMode", firstNonBlank(job.getApplyMode(), "EXTERNAL_APPLY")))
                .bind("easyApplyAvailable", Boolean.TRUE.equals(job.getEasyApplyAvailable()))
                .bind("connectionsCount", job.getConnectionsCount() == null ? 0 : job.getConnectionsCount())
                .bind("tags", tags.toArray(String[]::new))
                .bind("tagsText", String.join(" ", tags))
                .bind("qualityReasons", qualityReasonsFor(job).toArray(String[]::new))
                .bind("sponsorshipLanguage",
                        cappedText("sponsorshipLanguage", firstNonBlank(job.getSponsorshipLanguage(), "UNKNOWN")))
                .bind("visaReasons", visaReasonsFor(job).toArray(String[]::new))
                .bind("sourcePayloadHash", payloadHash(source, job))
                .bind("now", now)
                .bind("expiresAt", expiresAt);

        spec = bindNullable(spec, "department", job.getDepartment(), String.class);
        spec = bindNullable(spec, "location", job.getLocation(), String.class);
        spec = bindNullable(spec, "workMode", job.getWorkMode(), String.class);
        spec = bindNullable(spec, "employmentType", job.getEmploymentType(), String.class);
        spec = bindNullable(spec, "descriptionText", job.getDescriptionText(), String.class);
        spec = bindNullable(spec, "salaryLabel", job.getSalaryLabel(), String.class);
        spec = bindNullable(spec, "applyUrl", job.getApplyUrl(), String.class);
        spec = bindNullable(spec, "jobUrl", job.getJobUrl(), String.class);
        spec = bindNullable(spec, "sourceUpdatedAt", sourceUpdatedAt, OffsetDateTime.class);
        spec = bindNullable(spec, "postedLabel", job.getPostedLabel(), String.class);
        spec = bindNullable(spec, "matchScore", job.getMatchScore(), Integer.class);
        spec = bindNullable(spec, "jobQualityScore", firstNonNull(job.getJobQualityScore(), job.getMatchScore()), Integer.class);
        spec = bindNullable(spec, "totalCompLabel", firstNonBlank(job.getTotalCompLabel(), inferTotalCompLabel(job.getSalaryLabel())), String.class);
        spec = bindNullable(spec, "compensationConfidence", firstNonBlank(job.getCompensationConfidence(), inferCompensationConfidence(job.getSalaryLabel())), String.class);
        spec = bindNullable(spec, "visaConfidenceScore", job.getVisaConfidenceScore(), Integer.class);
        spec = bindNullable(spec, "requiresUsWorkAuthorization", job.getRequiresUsWorkAuthorization(), Boolean.class);
        spec = bindNullable(spec, "contractOrStaffingRisk", job.getContractOrStaffingRisk(), Boolean.class);
        spec = bindNullable(spec, "stemOptRisk", job.getStemOptRisk(), Boolean.class);
        spec = bindNullable(spec, "h1bTransferFit", job.getH1bTransferFit(), Boolean.class);
        spec = bindNullable(spec, "capExemptFit", job.getCapExemptFit(), Boolean.class);
        spec = bindNullableShort(spec, "experienceYears", job.getExperienceYears());
        spec = bindNullable(spec, "seniorityLabel", job.getSeniorityLabel(), String.class);

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
                            ) AS compensation_confidence,
                            p.sponsorship_language,
                            p.visa_confidence_score,
                            p.visa_reasons,
                            p.requires_us_work_authorization,
                            p.contract_or_staffing_risk,
                            p.stem_opt_risk,
                            p.h1b_transfer_fit,
                            p.cap_exempt_fit,
                            p.experience_years,
                            p.seniority_label
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
                        .sponsorshipLanguage(row.get("sponsorship_language", String.class))
                        .visaConfidenceScore(row.get("visa_confidence_score", Integer.class))
                        .visaReasons(tagsFrom(row.get("visa_reasons", Object.class)))
                        .requiresUsWorkAuthorization(row.get("requires_us_work_authorization", Boolean.class))
                        .contractOrStaffingRisk(row.get("contract_or_staffing_risk", Boolean.class))
                        .stemOptRisk(row.get("stem_opt_risk", Boolean.class))
                        .h1bTransferFit(row.get("h1b_transfer_fit", Boolean.class))
                        .capExemptFit(row.get("cap_exempt_fit", Boolean.class))
                        .seniorityLabel(row.get("seniority_label", String.class))
                        .experienceYears(row.get("experience_years", Integer.class))
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
                            quality_reasons = CAST(:qualityReasons AS TEXT[]),
                            total_comp_label = :totalCompLabel,
                            compensation_confidence = :compensationConfidence,
                            sponsorship_language = :sponsorshipLanguage,
                            visa_confidence_score = :visaConfidenceScore,
                            visa_reasons = CAST(:visaReasons AS TEXT[]),
                            requires_us_work_authorization = :requiresUsWorkAuthorization,
                            contract_or_staffing_risk = :contractOrStaffingRisk,
                            stem_opt_risk = :stemOptRisk,
                            h1b_transfer_fit = :h1bTransferFit,
                            cap_exempt_fit = :capExemptFit,
                            experience_years = CAST(:experienceYears AS SMALLINT),
                            seniority_label = :seniorityLabel,
                            updated_at = :now
                        WHERE source_type = :sourceType
                          AND source_board_token = :sourceBoardToken
                          AND external_job_id = :externalJobId
                        """)
                .bind("sourceType", normalizeSource(detail.getSourceType()))
                .bind("sourceBoardToken", detail.getSourceBoardToken().trim())
                .bind("externalJobId", detail.getExternalJobId().trim())
                .bind("qualityReasons", qualityReasonsFor(detail).toArray(String[]::new))
                .bind("sponsorshipLanguage", firstNonBlank(detail.getSponsorshipLanguage(), "UNKNOWN"))
                .bind("visaReasons", visaReasonsFor(detail).toArray(String[]::new))
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
        spec = bindNullable(spec, "visaConfidenceScore", detail.getVisaConfidenceScore(), Integer.class);
        spec = bindNullable(spec, "requiresUsWorkAuthorization", detail.getRequiresUsWorkAuthorization(), Boolean.class);
        spec = bindNullable(spec, "contractOrStaffingRisk", detail.getContractOrStaffingRisk(), Boolean.class);
        spec = bindNullable(spec, "stemOptRisk", detail.getStemOptRisk(), Boolean.class);
        spec = bindNullable(spec, "h1bTransferFit", detail.getH1bTransferFit(), Boolean.class);
        spec = bindNullable(spec, "capExemptFit", detail.getCapExemptFit(), Boolean.class);
        spec = bindNullableShort(spec, "experienceYears", detail.getExperienceYears());
        spec = bindNullable(spec, "seniorityLabel", detail.getSeniorityLabel(), String.class);

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
                          AND source_type <> 'AIRRAL_INTERNAL'
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

    /**
     * Hard-deletes postings that have been inactive for at least {@code purgeAfterDays},
     * reclaiming the disk that {@link #expireOldJobs(int)} only soft-deletes.
     *
     * <p>Rows still referenced by a candidate's saved job or fit result are kept:
     * those tables hold {@code source_job_key} as a plain column with no foreign key,
     * and the applicant tracker resolves job details live, so deleting a referenced
     * posting degrades the tracker to "Saved job / Source unavailable".
     *
     * <p>The {@code deleted_at} grace window also protects against a transient board
     * outage: a source that returns HTTP 404 is auto-disabled and all of its postings
     * deactivated, so purging on deactivation alone would drop a whole board over a blip.
     */
    public Mono<Long> purgeExpiredJobs(int purgeAfterDays) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(Math.max(1, purgeAfterDays));
        return databaseClient.sql("""
                        DELETE FROM external_job_postings p
                        WHERE p.is_active = false
                          AND p.source_type <> 'AIRRAL_INTERNAL'
                          AND p.deleted_at IS NOT NULL
                          AND p.deleted_at < :cutoff
                          AND NOT EXISTS (
                              SELECT 1 FROM candidate_saved_jobs s
                              WHERE s.source_job_key = p.source_job_key
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM candidate_job_fit_results f
                              WHERE f.source_job_key = p.source_job_key
                          )
                        """)
                .bind("cutoff", cutoff)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> deactivateInternalJob(Long internalJobId) {
        if (internalJobId == null) {
            return Mono.just(0L);
        }

        return databaseClient.sql("""
                        UPDATE external_job_postings
                        SET is_active = false,
                            deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE source_type = 'AIRRAL_INTERNAL'
                          AND external_job_id = :internalJobId
                          AND is_active = true
                        """)
                .bind("internalJobId", String.valueOf(internalJobId))
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> deactivateStaleInternalJobs() {
        return databaseClient.sql("""
                        UPDATE external_job_postings p
                        SET is_active = false,
                            deleted_at = COALESCE(p.deleted_at, CURRENT_TIMESTAMP),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE p.source_type = 'AIRRAL_INTERNAL'
                          AND p.is_active = true
                          AND NOT EXISTS (
                              SELECT 1
                              FROM jobs j
                              WHERE j.id::text = p.external_job_id
                                AND j.status = 'OPEN'
                          )
                        """)
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

    public Mono<Long> disableSource(Long sourceId, String reason) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE external_job_sources
                        SET is_active = false,
                            last_synced_at = CURRENT_TIMESTAMP,
                            last_error = :lastError,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :sourceId
                        """)
                .bind("sourceId", sourceId);
        spec = bindNullable(spec, "lastError", truncate(reason, 2000), String.class);
        return spec.fetch().rowsUpdated();
    }

    public Mono<Long> deactivatePostingsForSource(Long sourceId) {
        return databaseClient.sql("""
                        UPDATE external_job_postings
                        SET is_active = false,
                            deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE job_source_id = :sourceId
                          AND is_active = true
                        """)
                .bind("sourceId", sourceId)
                .fetch()
                .rowsUpdated();
    }

    public Mono<CandidateJobSummaryResponse> findActiveJobSummary(String sourceJobKey) {
        if (sourceJobKey == null || sourceJobKey.isBlank()) {
            return Mono.empty();
        }

        return databaseClient.sql("""
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
                            COALESCE(p.total_comp_label, 'Benchmark needed') AS total_comp_label,
                            COALESCE(p.compensation_confidence, 'NEEDS_BENCHMARK') AS compensation_confidence,
                            p.sponsorship_language,
                            p.visa_confidence_score,
                            p.visa_reasons,
                            p.requires_us_work_authorization,
                            p.contract_or_staffing_risk,
                            p.stem_opt_risk,
                            p.h1b_transfer_fit,
                            p.cap_exempt_fit,
                            p.experience_years,
                            p.seniority_label
                        FROM external_job_postings p
                        JOIN external_companies c ON c.id = p.company_id
                        WHERE p.source_job_key = :sourceJobKey
                          AND p.is_active = true
                          AND p.expires_at > CURRENT_TIMESTAMP
                        LIMIT 1
                        """)
                .bind("sourceJobKey", sourceJobKey.trim())
                .map((row, metadata) -> withStoreFallbacks(CandidateJobSummaryResponse.builder()
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
                        .sponsorshipLanguage(row.get("sponsorship_language", String.class))
                        .visaConfidenceScore(row.get("visa_confidence_score", Integer.class))
                        .visaReasons(tagsFrom(row.get("visa_reasons", Object.class)))
                        .requiresUsWorkAuthorization(row.get("requires_us_work_authorization", Boolean.class))
                        .contractOrStaffingRisk(row.get("contract_or_staffing_risk", Boolean.class))
                        .stemOptRisk(row.get("stem_opt_risk", Boolean.class))
                        .h1bTransferFit(row.get("h1b_transfer_fit", Boolean.class))
                        .capExemptFit(row.get("cap_exempt_fit", Boolean.class))
                        .seniorityLabel(row.get("seniority_label", String.class))
                        .experienceYears(row.get("experience_years", Integer.class))
                        .build()))
                .one();
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
        if (job.getSponsorshipLanguage() == null || job.getSponsorshipLanguage().isBlank()) {
            job.setSponsorshipLanguage("UNKNOWN");
        }
        if (job.getVisaReasons() == null || job.getVisaReasons().isEmpty()) {
            job.setVisaReasons(visaReasonsFor(job));
        }
        if (job.getVisaConfidenceScore() == null) {
            job.setVisaConfidenceScore("NO_SPONSORSHIP".equals(job.getSponsorshipLanguage()) ? 8 : 55);
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
        if (detail.getSponsorshipLanguage() == null || detail.getSponsorshipLanguage().isBlank()) {
            detail.setSponsorshipLanguage("UNKNOWN");
        }
        if (detail.getVisaReasons() == null || detail.getVisaReasons().isEmpty()) {
            detail.setVisaReasons(visaReasonsFor(detail));
        }
        if (detail.getVisaConfidenceScore() == null) {
            detail.setVisaConfidenceScore("NO_SPONSORSHIP".equals(detail.getSponsorshipLanguage()) ? 8 : 55);
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
        return spec.bind(key, capped(key, value));
    }

    /**
     * Applies this key's column width, if it has one. Placed on the bind rather
     * than at each call site so a bind added later is capped by default -- every
     * text bind in this class was uncapped until now, and the failure is silent.
     */
    private Object capped(String key, Object value) {
        Integer limit = TEXT_COLUMN_LIMITS.get(key);
        if (limit == null || !(value instanceof String text)) {
            return value;
        }
        return truncate(text, limit);
    }

    /** For the non-nullable bind chain, which does not route through bindNullable. */
    private String cappedText(String key, String value) {
        Object result = capped(key, value);
        return result == null ? null : String.valueOf(result);
    }

    private DatabaseClient.GenericExecuteSpec bindNullableShort(
            DatabaseClient.GenericExecuteSpec spec,
            String key,
            Integer value) {
        if (value == null) {
            return spec.bindNull(key, Short.class);
        }
        return spec.bind(key, value.shortValue());
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
                job.getDescriptionText());
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

    private List<String> visaReasonsFor(CandidateJobSummaryResponse job) {
        if (job.getVisaReasons() != null && !job.getVisaReasons().isEmpty()) {
            return job.getVisaReasons();
        }

        List<String> reasons = new java.util.ArrayList<>();
        if ("SPONSORS".equalsIgnoreCase(job.getSponsorshipLanguage())) {
            reasons.add("Posting mentions sponsorship");
        } else if ("NO_SPONSORSHIP".equalsIgnoreCase(job.getSponsorshipLanguage())) {
            reasons.add("Posting says sponsorship is not available");
        } else if ("AUTHORIZATION_REQUIRED".equalsIgnoreCase(job.getSponsorshipLanguage())) {
            reasons.add("Posting requires US work authorization");
        } else {
            reasons.add("Sponsorship not stated");
        }
        if (Boolean.TRUE.equals(job.getContractOrStaffingRisk())) {
            reasons.add("Contract/staffing language needs review");
        }
        if (Boolean.TRUE.equals(job.getCapExemptFit())) {
            reasons.add("Possible cap-exempt employer signal");
        }
        return reasons.stream().limit(4).toList();
    }

    private List<String> visaReasonsFor(CandidateJobDetailResponse detail) {
        if (detail.getVisaReasons() != null && !detail.getVisaReasons().isEmpty()) {
            return detail.getVisaReasons();
        }

        List<String> reasons = new java.util.ArrayList<>();
        if ("SPONSORS".equalsIgnoreCase(detail.getSponsorshipLanguage())) {
            reasons.add("Posting mentions sponsorship");
        } else if ("NO_SPONSORSHIP".equalsIgnoreCase(detail.getSponsorshipLanguage())) {
            reasons.add("Posting says sponsorship is not available");
        } else if ("AUTHORIZATION_REQUIRED".equalsIgnoreCase(detail.getSponsorshipLanguage())) {
            reasons.add("Posting requires US work authorization");
        } else {
            reasons.add("Sponsorship not stated");
        }
        if (Boolean.TRUE.equals(detail.getContractOrStaffingRisk())) {
            reasons.add("Contract/staffing language needs review");
        }
        if (Boolean.TRUE.equals(detail.getCapExemptFit())) {
            reasons.add("Possible cap-exempt employer signal");
        }
        return reasons.stream().limit(4).toList();
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

    /**
     * Turns the hand-set filters into predicates, so they constrain the query
     * rather than whatever the query happened to return first.
     *
     * <p>Deliberately mirrors the Java versions in CandidateJobSearchService
     * rather than replacing them: the personalized path unions several retrieval
     * queries and still narrows in memory afterwards, so both implementations
     * have to agree. Where one changes, change the other -- a candidate signed in
     * and signed out must get the same answer about the same posting.
     *
     * <p>UNKNOWN work mode is not claimed by ONSITE or HYBRID, because it means
     * the employer did not say; a location mentioning remote is still evidence
     * and counts toward REMOTE. Experience uses half-open ranges so a posting
     * lands in exactly one bucket, and falls back to the level label only when
     * there is no year count -- a posting stating neither satisfies no bucket.
     */
    private void appendExplicitFilters(StringBuilder sql, ExplicitJobFilters filters) {
        if (filters == null || !filters.any()) {
            return;
        }

        if (filters.hasWorkMode()) {
            if ("REMOTE".equals(filters.normalizedWorkMode())) {
                sql.append("""
                         AND (
                            p.work_mode = 'REMOTE'
                            OR (COALESCE(p.work_mode, 'UNKNOWN') = 'UNKNOWN'
                                AND LOWER(COALESCE(p.location, '')) LIKE '%remote%')
                         )
                        """);
            } else {
                sql.append(" AND p.work_mode = :filterWorkMode");
            }
        }

        if (filters.wantsPostedSalary()) {
            sql.append("""
                     AND p.salary_label IS NOT NULL
                     AND p.salary_label <> ''
                     AND LOWER(p.salary_label) NOT LIKE '%not listed%'
                    """);
        }

        if (filters.wantsVisaFriendly()) {
            sql.append(" AND COALESCE(p.sponsorship_language, 'UNKNOWN') IN ('UNKNOWN', 'SPONSORS')");
        }

        if (filters.hasExperienceLevel()) {
            String years = switch (filters.normalizedExperienceLevel()) {
                case "entry" -> "p.experience_years < 2";
                case "mid" -> "p.experience_years >= 2 AND p.experience_years < 5";
                case "senior" -> "p.experience_years >= 5 AND p.experience_years < 8";
                case "staff" -> "p.experience_years >= 8";
                default -> null;
            };
            String levels = switch (filters.normalizedExperienceLevel()) {
                case "entry" -> "('intern', 'entry')";
                case "mid" -> "('mid')";
                case "senior" -> "('senior')";
                case "staff" -> "('staff+', 'lead', 'director+')";
                default -> null;
            };

            if (years != null) {
                sql.append(" AND ((p.experience_years IS NOT NULL AND ").append(years).append(")")
                   .append(" OR (p.experience_years IS NULL AND LOWER(p.seniority_label) IN ")
                   .append(levels).append("))");
            }
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, MAX_RECOMMENDED_QUERY_LIMIT));
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
