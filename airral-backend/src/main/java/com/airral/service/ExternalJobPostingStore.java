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
            Map.entry("salaryPeriod", 12),
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
                    -- Was left out of every summary projection while the detail query had it,
                    -- so the list API answered with salaryPeriod null on all 40 cards measured
                    -- while 38 of those cards had "/hr" sitting inside salary_label. The unit
                    -- survived only as display text, which means no consumer of the list could
                    -- tell an hourly rate from an annual one -- it could only re-read English.
                    p.salary_period,
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
                            WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' OR (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]') THEN 'Benchmark needed'
                            ELSE 'Base listed'
                        END
                    ) AS total_comp_label,
                    COALESCE(
                        p.compensation_confidence,
                        CASE
                            WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' OR (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]') THEN 'NEEDS_BENCHMARK'
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
            // Exempts the employer's own postings. Their lifecycle is the
            // employer's: deactivateStaleInternalJobs closes them when the
            // underlying job stops being OPEN. Ageing them out of the feed
            // because nobody edited the record for a while removed live roles
            // from a paying customer's own listing.
            sql.append(" AND (p.source_type = 'AIRRAL_INTERNAL' OR p.source_updated_at >= :sourceCutoff)");
        }
        if (company != null && !company.isBlank()) {
            sql.append(" AND LOWER(c.name) LIKE :company");
        }
        if (query != null && !query.isBlank()) {
            sql.append("""
                     AND (
                        p.search_vector @@ plainto_tsquery('english', :query)
                        OR LOWER(p.title) LIKE :queryLike
                        OR p.company_id IN (
                            SELECT ec.id FROM external_companies ec
                            WHERE LOWER(ec.name) LIKE :queryLike
                        )
                     )
                    """);
        }

        appendExplicitFilters(sql, filters);

        // Newest day first, best of that day within it. Recency alone gave quality
        // no say at all, and the tiebreaker it replaces -- match_score -- is a
        // title keyword check with three possible values, computed without a
        // profile, so it was ordering the feed on almost nothing.
        sql.append(" ORDER BY DATE_TRUNC('day', p.source_updated_at) DESC NULLS LAST,"
                + " p.job_quality_score DESC NULLS LAST,"
                + " p.source_updated_at DESC NULLS LAST, p.last_seen_at DESC LIMIT :limit OFFSET :offset");

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
                        .salaryPeriod(row.get("salary_period", String.class))
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

    /**
     * Everything a sitemap needs, and nothing more.
     *
     * <p>Deliberately not the full summary query: a sitemap wants an address and
     * a date for tens of thousands of rows, and pulling the whole posting for
     * each -- descriptions included -- to print two fields would be wasteful on a
     * shared-core instance that is also serving the site.
     *
     * <p>Ordered by recency so that if the corpus ever outgrows one sitemap file,
     * the entries that get cut are the stalest.
     */
    public Flux<SitemapEntry> findSitemapEntries(int limit) {
        return databaseClient.sql("""
                        SELECT p.source_type, p.source_board_token, p.external_job_id, p.source_updated_at
                        FROM external_job_postings p
                        WHERE p.is_active = true
                          AND p.expires_at > CURRENT_TIMESTAMP
                          AND p.source_type <> 'AIRRAL_INTERNAL'
                        ORDER BY p.source_updated_at DESC NULLS LAST
                        LIMIT :limit
                        """)
                .bind("limit", Math.max(1, limit))
                .map((row, meta) -> new SitemapEntry(
                        row.get("source_type", String.class),
                        row.get("source_board_token", String.class),
                        row.get("external_job_id", String.class),
                        row.get("source_updated_at", OffsetDateTime.class)))
                .all();
    }

    /** One indexable posting: its address, and when the employer last touched it. */
    public record SitemapEntry(
            String sourceType,
            String boardToken,
            String externalJobId,
            OffsetDateTime sourceUpdatedAt) {
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
        return findJobsBySkills(skills, maxAgeDays, limit, ExplicitJobFilters.none());
    }

    public Flux<CandidateJobSummaryResponse> findJobsBySkills(
            List<String> skills,
            int maxAgeDays,
            int limit,
            ExplicitJobFilters filters) {
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
                    -- Same omission as findRecommendedJobs: this builds the same card, so it
                    -- has to carry the same unit.
                    p.salary_period,
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
                            WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' OR (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]') THEN 'Benchmark needed'
                            ELSE 'Base listed'
                        END
                    ) AS total_comp_label,
                    COALESCE(
                        p.compensation_confidence,
                        CASE
                            WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' OR (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]') THEN 'NEEDS_BENCHMARK'
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
                    -- ts_rank was here. It forced a detoast of every matching row's
                    -- tsvector purely to order them, and the enriched vector made that
                    -- expensive: 56 -> 2,283 shared buffers once descriptions were
                    -- included, against 486 without it. Nothing read the value -- it
                    -- existed only for the ORDER BY below -- and recency is a defensible
                    -- order for a skills match whose relevance signal is a four-word tag
                    -- vocabulary anyway.
                    p.source_updated_at AS relevance_placeholder
                FROM external_job_postings p
                JOIN external_companies c ON c.id = p.company_id
                WHERE p.is_active = true
                  AND p.expires_at > CURRENT_TIMESTAMP
                  AND p.search_vector @@ to_tsquery('english', :tsQuery)
                """);

        if (maxAgeDays > 0) {
            // Exempts the employer's own postings. Their lifecycle is the
            // employer's: deactivateStaleInternalJobs closes them when the
            // underlying job stops being OPEN. Ageing them out of the feed
            // because nobody edited the record for a while removed live roles
            // from a paying customer's own listing.
            sql.append(" AND (p.source_type = 'AIRRAL_INTERNAL' OR p.source_updated_at >= :sourceCutoff)");
        }

        appendExplicitFilters(sql, filters);

        sql.append(" ORDER BY p.source_updated_at DESC NULLS LAST LIMIT :limit");

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("tsQuery", tsQuery)
                .bind("limit", resolvedLimit);

        // appendExplicitFilters emits :filterWorkMode for the non-REMOTE modes, so
        // this query has to bind it too. Without it a signed-in candidate filtering
        // by Hybrid or On-site gets an unbound-parameter failure from the skills
        // retrieval batch.
        if (filters != null && filters.hasWorkMode()
                && !"REMOTE".equals(filters.normalizedWorkMode())) {
            spec = spec.bind("filterWorkMode", filters.normalizedWorkMode());
        }

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
                        .salaryPeriod(row.get("salary_period", String.class))
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
        // Counted from when we last saw the posting on its board, not from when
        // the employer published it. Publish-date sources -- Ashby,
        // SmartRecruiters, BambooHR -- never move that date, so under the old rule
        // a role published more than retention-days ago arrived already expired
        // and could never recover: the same frozen date failed the same test on
        // every future run. A still-listed job is an open job, whatever its
        // publish date says, and "how long since we last confirmed it exists" is
        // the question retention is actually asking.
        OffsetDateTime expiresAt = now.plusDays(Math.max(1, retentionDays));

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
                            salary_period,
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
                            :salaryPeriod,
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
                            -- The latest word from the source wins, but silence does
                            -- not. This was a plain EXCLUDED assignment, which was
                            -- safe only while work mode came from the title and the
                            -- location -- inputs every run has. It now also comes from
                            -- the body, and Workday and SmartRecruiters ship no body in
                            -- their list payload, so those runs emit UNKNOWN. Without
                            -- this guard the sync would blank the column four hours
                            -- after the hydration pass filled it, and the fix would
                            -- have looked like it worked for exactly one interval --
                            -- the same failure the derived block further down records.
                            -- Shaped like the derived-column guards below, and for the same
                            -- reason: work mode now depends on a body, and the Workday and
                            -- SmartRecruiters list payloads carry none, so those runs emit
                            -- UNKNOWN and a plain EXCLUDED assignment would blank a hydrated
                            -- value every four hours.
                            --
                            -- Keyed on whether THIS RUN had a body, not on whether the answer
                            -- happens to be UNKNOWN. Keying on the answer looked equivalent and
                            -- was not: it made the column permanently unclearable, so an
                            -- employer who moved a job back into the office could never stop it
                            -- reading REMOTE. A run that read a body is entitled to say UNKNOWN.
                            work_mode = CASE
                                WHEN NULLIF(EXCLUDED.description_text, '') IS NOT NULL
                                THEN EXCLUDED.work_mode
                                ELSE COALESCE(external_job_postings.work_mode, EXCLUDED.work_mode)
                            END,
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
                            -- Tied to the label's guard above on purpose. If the stored
                            -- label survives, the stored period must survive with it;
                            -- taking one from this run and the other from a previous one
                            -- would pair "$40/hr" with a null interval, or worse an
                            -- interval from a different figure entirely.
                            salary_period = CASE
                                WHEN EXCLUDED.salary_label IS NULL
                                  OR LOWER(EXCLUDED.salary_label) LIKE '%not listed%'
                                THEN external_job_postings.salary_period
                                ELSE EXCLUDED.salary_period
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
                            -- Only extended when it is actually running short.
                            --
                            -- expires_at sits in idx_ejp_active_feed, so rewriting it on
                            -- every sighting changes an indexed column and denies Postgres
                            -- a HOT update -- every index on the table gets a new entry for
                            -- a row whose content did not change. The sync now upserts
                            -- everything a board lists rather than only the fresh ones, so
                            -- that happens to the whole corpus every four hours.
                            --
                            -- Half a window of slack keeps the guarantee intact: a posting
                            -- still seen is never within half a retention period of
                            -- expiring, and one that stops being seen still ages out on
                            -- schedule from its last extension.
                            expires_at = CASE
                                WHEN external_job_postings.expires_at
                                     < CURRENT_TIMESTAMP + (:retentionInterval)::interval
                                THEN EXCLUDED.expires_at
                                ELSE external_job_postings.expires_at
                            END,
                            deleted_at = NULL,
                            updated_at = EXCLUDED.updated_at,
                            -- Includes the body, so V21's enrichment survives a re-sync. The 2000
                            -- character cap matches the migration that introduced it.
                            -- Rebuilt only when the posting actually changed.
                            --
                            -- source_payload_hash has existed since V7 with no reader that
                            -- compared it. It has one now, and this is the write worth
                            -- avoiding: the enriched vector runs to ~116 lexemes against
                            -- ~17 before, and recomputing it re-indexes the row in a GIN
                            -- index whose contents are identical to what was already there.
                            search_vector = CASE
                                WHEN external_job_postings.source_payload_hash
                                     IS DISTINCT FROM EXCLUDED.source_payload_hash
                                     OR external_job_postings.search_vector IS NULL
                                THEN to_tsvector('english', CONCAT_WS(' ', EXCLUDED.title, EXCLUDED.department, EXCLUDED.location, EXCLUDED.employment_type, EXCLUDED.source_name, array_to_string(EXCLUDED.tags, ' '), LEFT(COALESCE(NULLIF(EXCLUDED.description_text, ''), external_job_postings.description_text), 2000)))
                                ELSE external_job_postings.search_vector
                            END
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
                .bind("expiresAt", expiresAt)
                .bind("retentionInterval", (Math.max(1, retentionDays) / 2) + " days");

        spec = bindNullable(spec, "department", job.getDepartment(), String.class);
        spec = bindNullable(spec, "location", job.getLocation(), String.class);
        spec = bindNullable(spec, "workMode", job.getWorkMode(), String.class);
        spec = bindNullable(spec, "employmentType", job.getEmploymentType(), String.class);
        spec = bindNullable(spec, "descriptionText", job.getDescriptionText(), String.class);
        spec = bindNullable(spec, "salaryLabel", job.getSalaryLabel(), String.class);
        spec = bindNullable(spec, "salaryPeriod", job.getSalaryPeriod(), String.class);
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
        return findStoredJobDetail(sourceType, boardToken, externalJobId, true);
    }

    /**
     * The same posting, accepted without a cached body.
     *
     * <p>For the degraded read path only. When the job board will not answer,
     * the row we already hold -- title, company, location, pay, apply link and
     * every decision signal -- is far more use to the candidate than an error
     * page, and it is available whether or not anyone has ever opened this job
     * before.
     *
     * <p>Keyed on the same three columns as existsActiveJob rather than on
     * source_job_key, so a posting the exists check just confirmed can never
     * come back missing here.
     */
    public Mono<CandidateJobDetailResponse> findStoredJobDetail(String sourceType, String boardToken, String externalJobId) {
        return findStoredJobDetail(sourceType, boardToken, externalJobId, false);
    }

    private Mono<CandidateJobDetailResponse> findStoredJobDetail(
            String sourceType, String boardToken, String externalJobId, boolean requireCachedBody) {
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
                            p.salary_period,
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
                                    WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' OR (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]') THEN 'Benchmark needed'
                                    ELSE 'Base listed'
                                END
                            ) AS total_comp_label,
                            COALESCE(
                                p.compensation_confidence,
                                CASE
                                    WHEN p.salary_label IS NULL OR LOWER(p.salary_label) LIKE '%not listed%' OR (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]') THEN 'NEEDS_BENCHMARK'
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
                        """
                        // Only description_html marks a real detail fetch, so only the
                        // cache-first read demands it.
                        //
                        // This used to accept either column, which was correct while
                        // both were written together by cacheJobDetail. The sync now
                        // writes description_text as well, so accepting it would let
                        // a summary-derived body satisfy the cache and stop the
                        // detail endpoint ever fetching the real one. That text is
                        // stripHtml output, and stripHtml collapses all whitespace to
                        // single spaces, so the page would render the posting as one
                        // unbroken paragraph -- and the frontend prefers
                        // descriptionHtml, which the sync never writes.
                        + (requireCachedBody ? " AND NULLIF(p.description_html, '') IS NOT NULL" : "")
                        + " LIMIT 1")
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
                        .salaryPeriod(row.get("salary_period", String.class))
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
                            -- Fill only. Both arms have to hold: the incoming value
                            -- must say something, and the stored one must not.
                            --
                            -- This column was absent from this list entirely, so
                            -- nothing the detail path derived could ever reach it --
                            -- which is why work mode stayed UNKNOWN on 74% of the
                            -- catalogue while sponsorship and seniority, derived from
                            -- the same body a line below, did not.
                            --
                            -- Guarded rather than plain because of who calls this. The
                            -- hydration pass is one caller; the live detail endpoint is
                            -- the other, and it writes on every cold job view. A
                            -- candidate opening a posting must not be able to downgrade
                            -- a work mode the source itself stated -- the read path
                            -- derives from prose, the source field is the better
                            -- signal, and an ordinary page view is no reason to
                            -- overwrite it. Writing UNKNOWN over a known value is
                            -- refused for the same reason.
                            work_mode = CASE
                                WHEN :workMode <> 'UNKNOWN'
                                  AND COALESCE(NULLIF(external_job_postings.work_mode, ''), 'UNKNOWN') = 'UNKNOWN'
                                THEN :workMode
                                ELSE external_job_postings.work_mode
                            END,
                            description_html = :descriptionHtml,
                            description_text = :descriptionText,
                            description_excerpt = :descriptionExcerpt,
                            salary_min = :salaryMin,
                            salary_max = :salaryMax,
                            salary_currency = :salaryCurrency,
                            salary_period = :salaryPeriod,
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
                // Normalised to the literal the CASE above compares against, so a
                // null or blank arrives as the same "says nothing" the column uses.
                .bind("workMode", cappedText("workMode", firstNonBlank(detail.getWorkMode(), "UNKNOWN")))
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
        spec = bindNullable(spec, "salaryPeriod", detail.getSalaryPeriod(), String.class);
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

    /**
     * Retires postings whose retention window has run out.
     *
     * <p>The window is now the only thing consulted, and it is measured from the
     * last time the posting was seen on its board. This used to also deactivate
     * anything whose publish date was older than the window, or missing -- which
     * is what made a still-open role from a publish-date source disappear and
     * stay disappeared. The retentionDays argument is kept because callers pass
     * it and expires_at is derived from it at write time.
     */
    /**
     * Recomputes job_quality_score from signals only the stored corpus has.
     *
     * <p>The score used to be seven "is this field populated" checks, computed in
     * the mapper from a single posting in isolation. Nothing in it was about the
     * job. It had a live spread of about one point, it appeared in no ORDER BY,
     * and it moved in the wrong direction as the data improved: rejecting
     * requisition-id departments and introducing an honest UNKNOWN work mode both
     * lowered it, because it was measuring our completeness rather than the
     * posting's worth.
     *
     * <p>Two signals here need the corpus rather than the row, which is why this
     * runs as a pass after the sync instead of in the mapper.
     *
     * <p>Repost churn: the same title from the same employer appearing many times
     * over. Measured on a real corpus, one title/company pair appeared eleven
     * times and another seven. A candidate applying to all eleven is applying to
     * one job, or to none.
     *
     * <p>Listing duration, from first_seen_at -- a column present since V7 with no
     * reader until now. A posting that has been continuously listed for months is
     * either evergreen pipeline-building or was never real; either way it is worth
     * less of a candidate's limited time than one posted last week. This signal is
     * weak until the corpus has history, and worthless on a fresh database, which
     * is worth knowing before reading anything into early numbers.
     *
     * <p>Only rows whose score actually changes are written. A blanket update
     * would rewrite the whole table after every sync and undo the HOT-update work
     * that keeps the four-hourly run off the indexes.
     */
    /**
     * A stored posting whose pay was read out of prose before the interval was.
     */
    public record ProsePayRow(Long id, String descriptionText, String salaryLabel) { }

    /**
     * Rows holding a pay figure with no interval, and a body to read one from.
     *
     * <p>The boards that state pay only in prose -- Workday, SmartRecruiters,
     * Workable, career pages -- have their label written by the detail cache, not
     * by the sync, and the detail endpoint answers from that cache. So a row
     * whose label was extracted before the interval logic existed would keep the
     * unit-less figure for good: the sync writes "Salary not listed" for these
     * sources and the upsert guard rightly preserves the stored label instead,
     * and nothing else recomputes it. This finds exactly those rows so the sync
     * can put the interval back on them.
     *
     * <p>Bounded per run on purpose. This is a catch-up pass over rows that
     * already have their body stored, not a hot path, and Cloud SQL here is a
     * db-f1-micro.
     */
    public Flux<ProsePayRow> findRowsMissingSalaryPeriod(int limit) {
        return databaseClient.sql("""
                        SELECT id, description_text, salary_label
                        FROM external_job_postings
                        WHERE is_active = true
                          AND salary_period IS NULL
                          AND description_text IS NOT NULL
                          AND description_text <> ''
                          AND salary_label IS NOT NULL
                          AND LOWER(salary_label) NOT LIKE '%not listed%'
                        ORDER BY id
                        LIMIT :limit
                        """)
                .bind("limit", Math.max(1, limit))
                .map((row, metadata) -> new ProsePayRow(
                        row.get("id", Long.class),
                        row.get("description_text", String.class),
                        row.get("salary_label", String.class)))
                .all();
    }

    /** Writes a recomputed label and its interval back onto one posting. */
    public Mono<Long> updateProsePay(Long id, String salaryLabel, String salaryPeriod) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE external_job_postings
                        SET salary_label = :salaryLabel,
                            salary_period = :salaryPeriod,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("now", OffsetDateTime.now(ZoneOffset.UTC));

        spec = bindNullable(spec, "salaryLabel", capped("salaryLabel", salaryLabel), String.class);
        spec = bindNullable(spec, "salaryPeriod", capped("salaryPeriod", salaryPeriod), String.class);

        return spec.fetch().rowsUpdated();
    }

    /**
     * A stored posting whose board ships no body in its list payload.
     */
    public record MissingBodyRow(Long id, String sourceType, String boardToken, String externalJobId) { }

    /**
     * Which rows the hydration pass considers work, shared by the select and by
     * the count that reports the backlog.
     *
     * <p>Shared for the same reason the sweep shares its predicate: a "remaining"
     * number taken from a different set of rows than the one being worked would
     * make the backlog look like it was draining, or not draining, for reasons
     * that had nothing to do with the pass.
     *
     * <p>Both description columns have to be empty. description_text alone is not
     * enough: the sync writes that column from the list payload for the boards
     * that do ship a body, so testing it alone would keep re-fetching Greenhouse
     * rows that already have everything. And description_html alone is not enough
     * either, because it is exactly the column a successful hydration fills, so a
     * row that gained a body must stop matching here or the pass would spend its
     * whole budget re-reading its own work.
     */
    static final String MISSING_BODY_PREDICATE = """
            WHERE is_active = true
              AND expires_at > CURRENT_TIMESTAMP
              AND source_type = ANY(CAST(:sourceTypes AS TEXT[]))
              AND NULLIF(description_text, '') IS NULL
              AND NULLIF(description_html, '') IS NULL
              AND NULLIF(source_board_token, '') IS NOT NULL
              AND NULLIF(external_job_id, '') IS NOT NULL
            """;

    /**
     * The work list for the hydration pass, spread across boards.
     *
     * <p>Ordered by each board's own rank first and by id second, so the run takes
     * one posting from every backlogged board before it takes a second from any of
     * them. This is the fairness mechanism, and it is the whole answer to a board
     * that can never succeed: one board in the catalogue answers 403 to this
     * service every time, and ordering by id alone would hand it whichever end of
     * the per-run budget its rows happened to sit at, on every run, forever.
     * Round-robin also costs nothing when there is only one backlogged board --
     * that board simply takes the whole budget, which is what you want while a
     * single large Workday tenant is the backlog.
     *
     * <p>perBoardLimit is the politeness ceiling on top of that, not the fairness
     * mechanism: it is the most requests one employer's server will take from a
     * single run however empty the field is. Set it near the per-run limit and it
     * effectively does not bind.
     *
     * <p>Bounded per run on purpose. Every row here becomes a request to another
     * company's server and an UPDATE on a db-f1-micro.
     */
    public Flux<MissingBodyRow> findRowsMissingDescription(
            List<String> sourceTypes, int perBoardLimit, int limit) {
        String[] normalized = normalizedSourceTypes(sourceTypes);
        if (normalized.length == 0) {
            return Flux.empty();
        }

        return databaseClient.sql("""
                        WITH ranked AS (
                            SELECT
                                id,
                                source_type,
                                source_board_token,
                                external_job_id,
                                -- random(), not id. Ordering by id puts the SAME rows at the
                                -- head of every board's slice on every run, and the caller gives
                                -- up on a board after three consecutive failures -- so three
                                -- permanently bad rows at the head of a board's id order block
                                -- that board's whole backlog for good, not just slow it down.
                                -- Bad rows are real here: a Workday requisition the tenant has
                                -- stopped serving answers 404 while the board still lists it, so
                                -- it never gains a body and never leaves this work list.
                                -- Sampling differently each run means a poison row costs one
                                -- attempt occasionally instead of blocking its board forever.
                                ROW_NUMBER() OVER (PARTITION BY job_source_id ORDER BY random()) AS board_rank
                            FROM external_job_postings
                        """ + MISSING_BODY_PREDICATE + """
                        )
                        SELECT id, source_type, source_board_token, external_job_id
                        FROM ranked
                        WHERE board_rank <= :perBoardLimit
                        ORDER BY board_rank
                        LIMIT :limit
                        """)
                .bind("sourceTypes", normalized)
                .bind("perBoardLimit", Math.max(1, perBoardLimit))
                .bind("limit", Math.max(1, limit))
                .map((row, metadata) -> new MissingBodyRow(
                        row.get("id", Long.class),
                        row.get("source_type", String.class),
                        row.get("source_board_token", String.class),
                        row.get("external_job_id", String.class)))
                .all();
    }

    /** How many postings the hydration pass still has to fetch, writing nothing. */
    public Mono<Long> countRowsMissingDescription(List<String> sourceTypes) {
        String[] normalized = normalizedSourceTypes(sourceTypes);
        if (normalized.length == 0) {
            return Mono.just(0L);
        }

        return databaseClient.sql("SELECT COUNT(*) AS total FROM external_job_postings "
                        + MISSING_BODY_PREDICATE)
                .bind("sourceTypes", normalized)
                .map((row, metadata) -> {
                    Long total = row.get("total", Long.class);
                    return total == null ? 0L : total;
                })
                .one()
                .defaultIfEmpty(0L);
    }

    private String[] normalizedSourceTypes(List<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return new String[0];
        }
        return sourceTypes.stream()
                .filter(Objects::nonNull)
                // Filtered before normalizing, not after: normalizeSource answers
                // "ALL" for a blank, and "ALL" is not a source_type any row holds.
                .filter(value -> !value.isBlank())
                .map(this::normalizeSource)
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * Rescores every live posting, and now also says why on the one signal that
     * separates postings from each other.
     *
     * <p>The count was already computed here and already spent: a title an employer
     * has six or more active postings under is penalised 20 points, and the measured
     * effect is large -- median quality 45 for rows in a 6+ group against 70 for a
     * singleton. None of that reached the candidate. The card showed "Fresh source
     * date", which was true of every posting, while the fact that actually moved the
     * score was computed and dropped. This writes it back as a reason, at the same
     * threshold the -20 band uses, so the chip and the penalty can never disagree.
     *
     * <p>The wording is the count and nothing more, and it has to be, because the
     * count cannot tell duplication from volume. The group is one employer and one
     * lowercased title across every location and every board: a retailer with a
     * cashier opening in 41 stores lands in the same 41-row group as an agency that
     * reposted one requisition 41 times. "41 copies" would be false for the first
     * employer and "41 reposts" for both, since no posting history was read. What
     * we can say is how many postings the employer has under that title, which is
     * the query. The candidate draws the inference; we supply the number.
     *
     * <p>For the same reason the chip does not say "live". The count is over
     * {@code is_active = true} alone, while every list a candidate sees also
     * requires {@code expires_at > CURRENT_TIMESTAMP}, and this runs before
     * expireOldJobs in the sync pipeline -- so a group can hold rows that are about
     * to be retired minutes later in the same run.
     *
     * <p>Deliberately no reason for the first_seen_at bands in the same expression,
     * tempting as a listing age is. first_seen_at records when WE first saw a
     * posting, not when the employer published it, and the column only exists from
     * migration V7, so the corpus holds a few weeks of it: measured over the live
     * data, the -15 band at 120 days has never fired for a single row. "Listed 147
     * days" would be an invention, and printing it here would make this change the
     * very thing it exists to remove.
     *
     * <p>Runs after every upsert in a sync run (see ExternalJobSyncService), which
     * is what makes the write durable: the upsert's quality_reasons branch takes
     * EXCLUDED whenever the run carried a body, so a reason written here before the
     * upserts would be erased by them.
     */
    public Mono<Long> recomputeJobQuality() {
        return databaseClient.sql("""
                        WITH churn AS (
                            SELECT company_id, LOWER(title) AS norm_title, COUNT(*) AS copies
                            FROM external_job_postings
                            WHERE is_active = true
                            GROUP BY 1, 2
                        ),
                        scored AS (
                            SELECT
                                p.id,
                                c.copies,
                                -- Rebuilt from scratch every run rather than appended to, so a
                                -- posting whose employer is down to one opening under this title
                                -- loses the chip instead of carrying a stale count for life.
                                -- The filter is coupled to the exact wording appended below:
                                -- change one and the other strands a chip that never clears.
                                ARRAY(
                                    SELECT reason
                                    FROM unnest(p.quality_reasons) WITH ORDINALITY AS u(reason, ord)
                                    WHERE reason NOT LIKE 'Employer has % postings with this title'
                                    ORDER BY ord
                                ) AS kept_reasons,
                                GREATEST(0, LEAST(100,
                                    50
                                    + CASE WHEN p.salary_label IS NOT NULL
                                            AND LOWER(p.salary_label) NOT LIKE '%not listed%'
                                           THEN 15 ELSE 0 END
                                    + CASE WHEN p.source_updated_at >= CURRENT_TIMESTAMP - INTERVAL '14 days'
                                           THEN 10 ELSE 0 END
                                    + CASE WHEN COALESCE(p.apply_url, p.job_url) IS NOT NULL
                                           THEN 5 ELSE 0 END
                                    + CASE
                                        WHEN p.first_seen_at < CURRENT_TIMESTAMP - INTERVAL '120 days' THEN -15
                                        WHEN p.first_seen_at < CURRENT_TIMESTAMP - INTERVAL '60 days' THEN -5
                                        ELSE 0
                                      END
                                    + CASE
                                        WHEN c.copies >= 6 THEN -20
                                        WHEN c.copies >= 3 THEN -10
                                        ELSE 0
                                      END
                                )) AS score
                            FROM external_job_postings p
                            JOIN churn c
                              ON c.company_id = p.company_id
                             AND c.norm_title = LOWER(p.title)
                            WHERE p.is_active = true
                        ),
                        reasoned AS (
                            SELECT
                                s.id,
                                s.score,
                                CASE
                                    WHEN s.copies >= 6
                                        -- The cast is not decoration: with an untyped literal
                                        -- on the left, "text || bigint" has no unambiguous
                                        -- operator to resolve to.
                                        THEN array_append(
                                                s.kept_reasons,
                                                'Employer has ' || s.copies::text
                                                    || ' postings with this title')
                                    ELSE s.kept_reasons
                                END AS reasons
                            FROM scored s
                        )
                        UPDATE external_job_postings t
                        SET job_quality_score = r.score,
                            quality_reasons = r.reasons,
                            updated_at = CURRENT_TIMESTAMP
                        FROM reasoned r
                        WHERE t.id = r.id
                          -- Both halves are needed. The score guard alone skipped a row
                          -- whose count crossed the threshold without moving the score
                          -- (the band is flat above 6), which would have left the chip
                          -- saying a number that is no longer true.
                          AND (t.job_quality_score IS DISTINCT FROM r.score
                               OR t.quality_reasons IS DISTINCT FROM r.reasons)
                        """)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> expireOldJobs(int retentionDays) {
        return databaseClient.sql("""
                        UPDATE external_job_postings
                        SET is_active = false,
                            deleted_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE is_active = true
                          AND source_type <> 'AIRRAL_INTERNAL'
                          AND expires_at <= CURRENT_TIMESTAMP
                        """)
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

    /**
     * Retires postings this source no longer lists.
     *
     * <p>Nothing detected that a job had been taken down. {@code last_seen_at}
     * had one reader and it was an ORDER BY tiebreaker, and
     * {@code source_payload_hash} had no reader that compared it -- so a posting
     * lived out its retention window whether or not it still existed, and the
     * worst thing this product can do to someone is send them to apply for a job
     * that is gone.
     *
     * <p>Every upsert stamps {@code last_seen_at}, so anything for this source
     * still carrying a timestamp from before the run started was absent from what
     * the board just returned.
     *
     * <p>The caller decides when this is safe to run; see the guards in
     * ExternalJobSyncService. A board that errors, returns nothing, or returns a
     * truncated page must not reach this, because "absent from the response" and
     * "absent from the board" are only the same thing when the response was
     * complete.
     */
    /**
     * Which rows the sweep acts on.
     *
     * <p>Shared by the retirement and by the count that reports it, so a dry run
     * cannot describe a different set of rows from the one that would actually be
     * retired. Retiring is the only irreversible thing the pipeline does -- an
     * unreversed retirement hardens into a hard delete once the purge window
     * passes -- so a report that drifted from the statement would be worse than
     * no report at all.
     */
    private static final String UNSEEN_POSTINGS_PREDICATE = """
            WHERE job_source_id = :sourceId
              AND is_active = true
              AND source_type <> 'AIRRAL_INTERNAL'
              AND last_seen_at < :seenSince
            """;

    public Mono<Long> deactivateUnseenPostings(Long sourceId, OffsetDateTime seenSince) {
        return databaseClient.sql("""
                        UPDATE external_job_postings
                        SET is_active = false,
                            deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                            updated_at = CURRENT_TIMESTAMP
                        """ + UNSEEN_POSTINGS_PREDICATE)
                .bind("sourceId", sourceId)
                .bind("seenSince", seenSince)
                .fetch()
                .rowsUpdated();
    }

    /** How many rows {@link #deactivateUnseenPostings} would retire, writing nothing. */
    public Mono<Long> countUnseenPostings(Long sourceId, OffsetDateTime seenSince) {
        return databaseClient.sql("SELECT COUNT(*) AS total FROM external_job_postings "
                        + UNSEEN_POSTINGS_PREDICATE)
                .bind("sourceId", sourceId)
                .bind("seenSince", seenSince)
                .map((row, metadata) -> {
                    Long total = row.get("total", Long.class);
                    return total == null ? 0L : total;
                })
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * A sample of the rows the sweep would retire, for a dry run to show.
     *
     * <p>A count alone does not tell you whether the guard is about to do
     * something sensible. Titles and their last-seen dates do.
     */
    public Flux<String> sampleUnseenPostings(Long sourceId, OffsetDateTime seenSince, int limit) {
        return databaseClient.sql("""
                        SELECT title, last_seen_at, job_url
                        FROM external_job_postings
                        """ + UNSEEN_POSTINGS_PREDICATE + """
                        ORDER BY last_seen_at
                        LIMIT :limit
                        """)
                .bind("sourceId", sourceId)
                .bind("seenSince", seenSince)
                .bind("limit", Math.max(1, limit))
                .map((row, metadata) -> String.format("%s (last seen %s) %s",
                        row.get("title", String.class),
                        row.get("last_seen_at", OffsetDateTime.class),
                        row.get("job_url", String.class)))
                .all();
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
                            -- Same omission as findRecommendedJobs: this builds the same card, so it
                            -- has to carry the same unit.
                            p.salary_period,
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
                        .salaryPeriod(row.get("salary_period", String.class))
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
            String applyUrl,
            String jobUrl,
            String department,
            String descriptionText) {
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add(isSalaryMissing(salaryLabel) ? "Needs salary benchmark" : "Employer salary listed");
        if (location != null && !location.isBlank() && !location.equalsIgnoreCase("Location not listed")) {
            reasons.add("Location clear");
        }
        // Kept in step with CandidateJobSearchService.buildQualityReasons, which is
        // the same list built on the write path. "Fresh source date" came out of
        // both: it fired whenever the source stated a timestamp, which is always.
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
                || salaryLabel.toLowerCase(Locale.US).contains("not listed")
                || isZeroAmount(salaryLabel);
    }

    /**
     * True when a label carries digits and every one of them is zero.
     *
     * <p>"USD $0k-$0k" is not an employer stating that a job pays nothing, it is
     * us having mangled the figure -- and stamping POSTED_BASE on it puts an
     * "Employer posted" chip under a zero. Labels with no digits at all
     * ("Competitive") are a different problem and are left alone here.
     */
    private boolean isZeroAmount(String salaryLabel) {
        boolean sawDigit = false;
        for (int i = 0; i < salaryLabel.length(); i++) {
            char c = salaryLabel.charAt(i);
            if (c >= '1' && c <= '9') {
                return false;
            }
            sawDigit |= c == '0';
        }

        return sawDigit;
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
                // No UNKNOWN fallback here, deliberately. inferWorkMode already
                // returns REMOTE for any posting whose title or location mentions
                // remote, so a row can never hold UNKNOWN and a remote-looking
                // location at the same time -- measured as 0 of 2,722 rows. The arm
                // that used to be here could not match, in three separate copies,
                // and no test reached it because the test helper never set a
                // location. If inferWorkMode ever stops reading the location, this
                // is where the fallback belongs.
                sql.append(" AND p.work_mode = 'REMOTE'");
            } else {
                sql.append(" AND p.work_mode = :filterWorkMode");
            }
        }

        if (filters.wantsPostedSalary()) {
            sql.append("""
                     AND p.salary_label IS NOT NULL
                     AND p.salary_label <> ''
                     AND LOWER(p.salary_label) NOT LIKE '%not listed%'
                     -- A mangled "$0k-$0k" is not a posted salary. Asking for
                     -- postings that state their pay must not return zeros.
                     AND NOT (p.salary_label ~ '[0-9]' AND p.salary_label !~ '[1-9]')
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
        // The body counts too, now that this hash decides whether the search
        // vector is rebuilt. Without it an employer who edited only the
        // description -- on a board that does not move its updated date when they
        // do -- would stay indexed against the old text indefinitely.
        joiner.add(String.valueOf(job.getDescriptionText()));
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
