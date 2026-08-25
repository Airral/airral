package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.dto.ashby.AshbyJobBoardResponse;
import com.airral.dto.bamboohr.BambooHrJobSummaryResponse;
import com.airral.dto.greenhouse.GreenhouseJobBoardResponse;
import com.airral.dto.lever.LeverPostingResponse;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.dto.schemaorg.SchemaOrgJobPosting;
import com.airral.dto.smartrecruiters.SmartRecruitersPostingResponse;
import com.airral.dto.workable.WorkableJobBoardResponse;
import com.airral.dto.workday.WorkdayJobDetailResponse;
import com.airral.dto.workday.WorkdayJobSearchResponse;
import com.airral.exception.BadRequestException;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class CandidateJobSearchService {

    private static final Logger log = LoggerFactory.getLogger(CandidateJobSearchService.class);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern US_STATE_PATTERN = Pattern.compile(
            "(?i)(^|[\\s,(-])(AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|IA|ID|IL|IN|KS|KY|LA|MA|MD|ME|MI|MN|MO|MS|MT|NC|ND|NE|NH|NJ|NM|NV|NY|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VA|VT|WA|WI|WV|WY)([\\s,).]|$)"
    );
    private static final Pattern US_STATE_NAME_PATTERN = Pattern.compile(
            "(?i)(^|[\\s,(-])(Alabama|Alaska|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|District\\s+of\\s+Columbia|Florida|Georgia|Hawaii|Idaho|Illinois|Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|Massachusetts|Michigan|Minnesota|Mississippi|Missouri|Montana|Nebraska|Nevada|New\\s+Hampshire|New\\s+Jersey|New\\s+Mexico|New\\s+York|North\\s+Carolina|North\\s+Dakota|Ohio|Oklahoma|Oregon|Pennsylvania|Rhode\\s+Island|South\\s+Carolina|South\\s+Dakota|Tennessee|Texas|Utah|Vermont|Virginia|Washington|West\\s+Virginia|Wisconsin|Wyoming)([\\s,).:-]|$)"
    );
    private static final Pattern US_COUNTRY_PATTERN = Pattern.compile("(?i)(^|[^a-z0-9])u\\.?s\\.?a?([^a-z0-9]|$)");
    private static final Pattern SALARY_RANGE_PATTERN = Pattern.compile(
            "(?i)\\$\\s?\\d[\\d,]*(?:\\.\\d+)?\\s*(?:to|-|–)\\s*\\$?\\s?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*[A-Z]{3})?(?:\\s*(?:per|/)?\\s*(?:year|hour|annually))?"
    );
    private static final Pattern JSON_LD_SCRIPT_PATTERN = Pattern.compile(
            "(?is)<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>"
    );
    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_MAX_AGE_DAYS = 60;
    private static final int LIVE_SOURCE_LIMIT = 500;
    private static final int PERSONALIZED_RANKING_WINDOW = 500;
    private static final int PERSONALIZED_RANKING_LIMIT = 2000;
    private static final int PERSONALIZED_RANKING_CACHE_MAX_ENTRIES = 256;
    private static final Duration PERSONALIZED_RANKING_CACHE_TTL = Duration.ofMinutes(5);
    private static final RoleMatchClassifier ROLE_MATCH_CLASSIFIER = new RoleMatchClassifier();
    private static final DateTimeFormatter MONTH_YEAR_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMMM yyyy")
            .toFormatter(Locale.US);
    private static final DateTimeFormatter SHORT_MONTH_YEAR_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMM yyyy")
            .toFormatter(Locale.US);
    private static final Set<String> DIRECT_SOURCE_TYPES = Set.of(
            "AIRRAL_INTERNAL",
            "GREENHOUSE",
            "LEVER",
            "ASHBY",
            "SMARTRECRUITERS",
            "RECRUITEE",
            "WORKABLE",
            "WORKDAY",
            "JOBVITE",
            "ICIMS",
            "BAMBOOHR",
            "JAZZHR"
    );
    private static final Set<String> GENERIC_ROLE_WORDS = Set.of(
            "engineer",
            "developer",
            "manager",
            "specialist",
            "analyst",
            "associate",
            "senior",
            "staff",
            "principal",
            "lead",
            "full",
            "level",
            "levels"
    );
            private static final Set<String> SEARCH_STOP_WORDS = Set.of(
                "and",
                "or",
                "the",
                "a",
                "an",
                "for",
                "to",
                "with",
                "in",
                "of",
                "on",
                "at",
                "by",
                "job",
                "jobs",
                "role",
                "position",
                "positions"
            );

    private final ExternalJobPostingStore externalJobPostingStore;
    private final GreenhouseJobBoardClient greenhouseClient;
    private final LeverJobBoardClient leverClient;
    private final AshbyJobBoardClient ashbyClient;
    private final SmartRecruitersJobBoardClient smartRecruitersClient;
    private final WorkableJobBoardClient workableClient;
    private final WorkdayJobBoardClient workdayClient;
    private final BambooHrJobBoardClient bambooHrClient;
    private final CareerPageJobBoardClient careerPageClient;
    private final CandidateProfileRepository candidateProfileRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String defaultGreenhouseBoard;
    private final String greenhouseBoards;
    private final String leverSites;
    private final String ashbyBoards;
    private final String smartRecruitersCompanies;
    private final String workableAccounts;
    private final String workdaySources;
    private final String jobvitePages;
    private final String icimsPages;
    private final String bambooHrCompanies;
    private final String jazzHrPages;
    private final String supportedCountry;
    private final int defaultMaxAgeDays;
    private final int maxLiveFallbackSources;
    private final int liveFallbackSourceConcurrency;
    private final Map<String, RankedJobsCacheEntry> personalizedRankingCache = new ConcurrentHashMap<>();

    public CandidateJobSearchService(
            ExternalJobPostingStore externalJobPostingStore,
            GreenhouseJobBoardClient greenhouseClient,
            LeverJobBoardClient leverClient,
            AshbyJobBoardClient ashbyClient,
            SmartRecruitersJobBoardClient smartRecruitersClient,
            WorkableJobBoardClient workableClient,
            WorkdayJobBoardClient workdayClient,
            BambooHrJobBoardClient bambooHrClient,
            CareerPageJobBoardClient careerPageClient,
            CandidateProfileRepository candidateProfileRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            @Value("${airral.jobs.greenhouse.default-board:airbnb}") String defaultGreenhouseBoard,
            @Value("${airral.jobs.greenhouse.board-tokens:airbnb}") String greenhouseBoards,
            @Value("${airral.jobs.lever.site-names:}") String leverSites,
            @Value("${airral.jobs.ashby.board-names:}") String ashbyBoards,
            @Value("${airral.jobs.smartrecruiters.company-identifiers:}") String smartRecruitersCompanies,
            @Value("${airral.jobs.workable.accounts:}") String workableAccounts,
            @Value("${airral.jobs.workday.sources:}") String workdaySources,
            @Value("${airral.jobs.jobvite.pages:}") String jobvitePages,
            @Value("${airral.jobs.icims.pages:}") String icimsPages,
            @Value("${airral.jobs.bamboohr.company-domains:}") String bambooHrCompanies,
            @Value("${airral.jobs.jazzhr.pages:}") String jazzHrPages,
            @Value("${airral.jobs.country:US}") String supportedCountry,
            @Value("${airral.jobs.max-age-days:60}") int defaultMaxAgeDays,
            @Value("${airral.jobs.live-fallback.max-sources:12}") int maxLiveFallbackSources,
            @Value("${airral.jobs.live-fallback.source-concurrency:4}") int liveFallbackSourceConcurrency) {
        this.externalJobPostingStore = externalJobPostingStore;
        this.greenhouseClient = greenhouseClient;
        this.leverClient = leverClient;
        this.ashbyClient = ashbyClient;
        this.smartRecruitersClient = smartRecruitersClient;
        this.workableClient = workableClient;
        this.workdayClient = workdayClient;
        this.bambooHrClient = bambooHrClient;
        this.careerPageClient = careerPageClient;
        this.candidateProfileRepository = candidateProfileRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.defaultGreenhouseBoard = defaultGreenhouseBoard;
        this.greenhouseBoards = greenhouseBoards;
        this.leverSites = leverSites;
        this.ashbyBoards = ashbyBoards;
        this.smartRecruitersCompanies = smartRecruitersCompanies;
        this.workableAccounts = workableAccounts;
        this.workdaySources = workdaySources;
        this.jobvitePages = jobvitePages;
        this.icimsPages = icimsPages;
        this.bambooHrCompanies = bambooHrCompanies;
        this.jazzHrPages = jazzHrPages;
        this.supportedCountry = supportedCountry;
        this.defaultMaxAgeDays = defaultMaxAgeDays <= 0
                ? DEFAULT_MAX_AGE_DAYS
                : Math.min(defaultMaxAgeDays, DEFAULT_MAX_AGE_DAYS);
        this.maxLiveFallbackSources = Math.max(0, maxLiveFallbackSources);
        this.liveFallbackSourceConcurrency = Math.max(1, Math.min(liveFallbackSourceConcurrency, 12));
    }

    public Flux<CandidateJobSummaryResponse> getRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company) {
        return getRecommendedJobs(source, boardToken, limit, maxAgeDays, query, company, null);
    }

    public Flux<CandidateJobSummaryResponse> getRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company,
            String candidateEmail) {
        int resolvedMaxAgeDays = normalizeMaxAgeDays(maxAgeDays);
        return externalJobPostingStore.findRecommendedJobs(source, boardToken, limit, resolvedMaxAgeDays, query, company)
                .collectList()
                .flatMapMany(cachedJobs -> cachedJobs.isEmpty()
                        ? getLiveFallbackJobs(source, boardToken, limit, resolvedMaxAgeDays, query, company)
                        : Flux.fromIterable(cachedJobs))
                .collectList()
                .flatMapMany(jobs -> personalizeJobs(candidateEmail, jobs));
    }

    public Mono<CandidateJobPageResponse> getRecommendedJobsPage(
            String source,
            String boardToken,
            Integer limit,
            Integer offset,
            Integer maxAgeDays,
            String query,
            String company) {
        return getRecommendedJobsPage(
                source, boardToken, limit, offset, maxAgeDays, query, company,
                null, null, null, null, null);
    }

    public Mono<CandidateJobPageResponse> getRecommendedJobsPage(
            String source,
            String boardToken,
            Integer limit,
            Integer offset,
            Integer maxAgeDays,
            String query,
            String company,
            String workMode,
            Boolean salaryPosted,
            String experienceLevel,
            Boolean visaFriendly,
            String candidateEmail) {
        int resolvedLimit = normalizeLimit(limit);
        int resolvedOffset = normalizeOffset(offset);
        int resolvedMaxAgeDays = normalizeMaxAgeDays(maxAgeDays);
        boolean hasExplicitFilters = hasExplicitFilters(workMode, salaryPosted, experienceLevel, visaFriendly);
        int queryLimit = hasExplicitFilters
                ? LIVE_SOURCE_LIMIT
                : Math.min(LIVE_SOURCE_LIMIT, resolvedLimit + 1);
        int queryOffset = hasExplicitFilters ? 0 : resolvedOffset;

        if (hasCandidateEmail(candidateEmail)) {
            return getPersonalizedRecommendedJobsPage(
                    source,
                    boardToken,
                    resolvedLimit,
                    resolvedOffset,
                    resolvedMaxAgeDays,
                    query,
                    company,
                    workMode,
                    salaryPosted,
                    experienceLevel,
                    visaFriendly,
                    candidateEmail);
        }

        return externalJobPostingStore.findRecommendedJobs(source, boardToken, queryLimit, queryOffset, resolvedMaxAgeDays, query, company)
                .collectList()
                .flatMap(cachedJobs -> cachedJobs.isEmpty()
                        ? getLiveFallbackJobs(source, boardToken, queryLimit, resolvedMaxAgeDays, query, company)
                                .skip(queryOffset)
                                .take(queryLimit)
                                .collectList()
                        : Mono.just(cachedJobs))
                .map(jobs -> applyExplicitFilters(jobs, workMode, salaryPosted, experienceLevel, visaFriendly))
                .map(jobs -> hasExplicitFilters
                        ? toRankedJobPage(jobs, resolvedLimit, resolvedOffset)
                        : toJobPage(jobs, resolvedLimit, resolvedOffset))
                .flatMap(page -> personalizePage(candidateEmail, page));
    }

    private Mono<CandidateJobPageResponse> getPersonalizedRecommendedJobsPage(
            String source,
            String boardToken,
            int resolvedLimit,
            int resolvedOffset,
            int resolvedMaxAgeDays,
            String query,
            String company,
            String workMode,
            Boolean salaryPosted,
            String experienceLevel,
            Boolean visaFriendly,
            String candidateEmail) {
        int rankingLimit = Math.min(
                PERSONALIZED_RANKING_LIMIT,
                Math.max(resolvedOffset + resolvedLimit + 1, PERSONALIZED_RANKING_WINDOW));

        return resolveCandidateMatchContext(candidateEmail)
                .flatMap(context -> getOrBuildPersonalizedRanking(
                                source,
                                boardToken,
                                rankingLimit,
                                resolvedMaxAgeDays,
                                query,
                                company,
                                workMode,
                                salaryPosted,
                                experienceLevel,
                                visaFriendly,
                                candidateEmail,
                                context)
                        .map(jobs -> toRankedJobPage(jobs, resolvedLimit, resolvedOffset)))
                .switchIfEmpty(loadRankingCandidates(source, boardToken, rankingLimit, resolvedMaxAgeDays, query, company)
                        .map(jobs -> applyExplicitFilters(jobs, workMode, salaryPosted, experienceLevel, visaFriendly))
                        .map(this::dedupeAndSort)
                        .map(jobs -> toRankedJobPage(jobs, resolvedLimit, resolvedOffset)));
    }

    private Mono<List<CandidateJobSummaryResponse>> getOrBuildPersonalizedRanking(
            String source,
            String boardToken,
            int rankingLimit,
            int resolvedMaxAgeDays,
            String query,
            String company,
            String workMode,
            Boolean salaryPosted,
            String experienceLevel,
            Boolean visaFriendly,
            String candidateEmail,
            CandidateMatchContext context) {
        String cacheKey = personalizedRankingCacheKey(
                source,
                boardToken,
                resolvedMaxAgeDays,
                query,
                company,
                workMode,
                salaryPosted,
                experienceLevel,
                visaFriendly,
                candidateEmail,
                context);

        RankedJobsCacheEntry cachedEntry = personalizedRankingCache.get(cacheKey);
        Instant now = Instant.now();
        if (cachedEntry != null && !cachedEntry.isExpired(now) && cachedEntry.rankingLimit() >= rankingLimit) {
            return Mono.just(cachedEntry.jobs());
        }

        return loadPersonalizedRankingCandidates(
                        source,
                        boardToken,
                        rankingLimit,
                        resolvedMaxAgeDays,
                        query,
                        company,
                        context)
                .map(jobs -> applyExplicitFilters(jobs, workMode, salaryPosted, experienceLevel, visaFriendly))
                .map(jobs -> rankPersonalizedJobs(jobs, context))
                .doOnNext(rankedJobs -> putPersonalizedRankingCache(cacheKey, rankingLimit, rankedJobs));
    }

    private String personalizedRankingCacheKey(
            String source,
            String boardToken,
            int resolvedMaxAgeDays,
            String query,
            String company,
            String workMode,
            Boolean salaryPosted,
            String experienceLevel,
            Boolean visaFriendly,
            String candidateEmail,
            CandidateMatchContext context) {
        return joinNonBlank("|",
                "v2",
                normalizeTerm(source),
                normalizeTerm(boardToken),
                String.valueOf(resolvedMaxAgeDays),
                normalizedTermText(query),
                normalizedTermText(company),
                normalizedTermText(workMode),
                String.valueOf(Boolean.TRUE.equals(salaryPosted)),
                normalizedTermText(experienceLevel),
                String.valueOf(Boolean.TRUE.equals(visaFriendly)),
                normalizeTerm(candidateEmail),
                contextFingerprint(context));
    }

    private String contextFingerprint(CandidateMatchContext context) {
        if (context == null) {
            return "no-context";
        }

        return joinNonBlank(";",
                "skills=" + canonicalTerms(context.skills()),
                "roles=" + canonicalTerms(context.targetRoles()),
                "must=" + canonicalTerms(context.mustHaveSkills()),
                "nice=" + canonicalTerms(context.niceToHaveSkills()),
                "avoid=" + canonicalTerms(context.avoidKeywords()),
                "headline=" + normalizeTerm(context.headline()),
                "loc=" + normalizeTerm(context.location()),
                "wm=" + normalizeTerm(context.preferredWorkMode()),
                "et=" + normalizeTerm(context.preferredEmploymentType()),
                "ns=" + Boolean.TRUE.equals(context.needsSponsorship()),
                "nsn=" + Boolean.TRUE.equals(context.needsSponsorshipNow()),
                "nsl=" + Boolean.TRUE.equals(context.needsSponsorshipLater()),
                "ev=" + Boolean.TRUE.equals(context.requiresEVerify()),
                "capx=" + Boolean.TRUE.equals(context.openToCapExemptEmployers()),
                "rel=" + Boolean.TRUE.equals(context.openToRelocation()),
                "salaryReq=" + Boolean.TRUE.equals(context.salaryRequired()),
                "easy=" + Boolean.TRUE.equals(context.easyApplyOnly()),
                "direct=" + Boolean.TRUE.equals(context.directCompanySourceOnly()),
                "noTakeHome=" + Boolean.TRUE.equals(context.noTakeHome()),
                "stability=" + Boolean.TRUE.equals(context.stabilityFirst()),
                "salaryMin=" + (context.salaryExpectationMin() == null ? "" : context.salaryExpectationMin().toPlainString()),
                "salaryMax=" + (context.salaryExpectationMax() == null ? "" : context.salaryExpectationMax().toPlainString()),
                "yoe=" + (context.yearsOfExperience() == null ? "" : context.yearsOfExperience()));
    }

    private String canonicalTerms(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private void putPersonalizedRankingCache(String cacheKey, int rankingLimit, List<CandidateJobSummaryResponse> rankedJobs) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }

        Instant expiresAt = Instant.now().plus(PERSONALIZED_RANKING_CACHE_TTL);
        personalizedRankingCache.put(cacheKey, new RankedJobsCacheEntry(rankingLimit, List.copyOf(rankedJobs), expiresAt));
        compactPersonalizedRankingCache();
    }

    private void compactPersonalizedRankingCache() {
        Instant now = Instant.now();
        personalizedRankingCache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (personalizedRankingCache.size() <= PERSONALIZED_RANKING_CACHE_MAX_ENTRIES) {
            return;
        }

        int overflow = personalizedRankingCache.size() - PERSONALIZED_RANKING_CACHE_MAX_ENTRIES;
        personalizedRankingCache.entrySet().stream()
                .sorted((left, right) -> left.getValue().expiresAt().compareTo(right.getValue().expiresAt()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(personalizedRankingCache::remove);
    }

    private Mono<List<CandidateJobSummaryResponse>> loadPersonalizedRankingCandidates(
            String source,
            String boardToken,
            int rankingLimit,
            int resolvedMaxAgeDays,
            String query,
            String company,
            CandidateMatchContext context) {
        List<Mono<List<CandidateJobSummaryResponse>>> batches = new ArrayList<>();
        batches.add(loadRankingCandidates(source, boardToken, rankingLimit, resolvedMaxAgeDays, query, company));

            // Keep expansion retrieval active even when a query is present so search remains personalized.
            retrievalQueriesFor(context, query).forEach(retrievalQuery -> batches.add(
                externalJobPostingStore.findRecommendedJobs(
                        source,
                        boardToken,
                        Math.max(75, rankingLimit / 2),
                        0,
                        resolvedMaxAgeDays,
                        retrievalQuery,
                        company)
                    .collectList()));

            // Skill-based retrieval: search DB for jobs matching candidate/profile skills and query signals.
            List<String> skillsForRetrieval = skillRetrievalTerms(context, query);
            if (!skillsForRetrieval.isEmpty()) {
                batches.add(externalJobPostingStore.findJobsBySkills(
                        skillsForRetrieval,
                        resolvedMaxAgeDays,
                        Math.max(75, rankingLimit / 2))
                    .collectList());
        }

        return Flux.fromIterable(batches)
                .concatMap(mono -> mono)
                .flatMapIterable(batch -> batch)
                .collectList()
                .map(this::dedupeAndSort);
    }

    /**
     * Build retrieval terms from the candidate's skills for DB search.
     * Prioritizes must-have skills, then regular skills, up to 12 terms.
     */
    private List<String> skillRetrievalTerms(CandidateMatchContext context, String query) {
        if (context == null) {
            return List.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        // Must-have skills go first — these are the strongest retrieval signal
        if (context.mustHaveSkills() != null) {
            context.mustHaveSkills().stream()
                    .filter(s -> s != null && s.length() > 1)
                    .forEach(terms::add);
        }
        // Then regular skills from profile/resume
        if (context.skills() != null) {
            context.skills().stream()
                    .filter(s -> s != null && s.length() > 1)
                    .forEach(terms::add);
        }
        // Then nice-to-have
        if (context.niceToHaveSkills() != null) {
            context.niceToHaveSkills().stream()
                    .filter(s -> s != null && s.length() > 1)
                    .forEach(terms::add);
        }

        String normalizedQuery = normalizedTermText(query);
        if (!normalizedQuery.isBlank()) {
            for (String token : normalizedQuery.split("\\s+")) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                if (token.length() <= 1 || SEARCH_STOP_WORDS.contains(token) || GENERIC_ROLE_WORDS.contains(token)) {
                    continue;
                }
                terms.add(token);
            }
        }

        return terms.stream().limit(20).toList();
    }

    private Mono<List<CandidateJobSummaryResponse>> loadRankingCandidates(
            String source,
            String boardToken,
            int rankingLimit,
            int resolvedMaxAgeDays,
            String query,
            String company) {
        return externalJobPostingStore.findRecommendedJobs(source, boardToken, rankingLimit, 0, resolvedMaxAgeDays, query, company)
                .collectList()
                .flatMap(cachedJobs -> cachedJobs.isEmpty()
                        ? getLiveFallbackJobs(source, boardToken, rankingLimit, resolvedMaxAgeDays, query, company)
                                .take(rankingLimit)
                                .collectList()
                        : Mono.just(cachedJobs));
    }

    public Flux<CandidateJobSummaryResponse> getLiveRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company) {
        return getLiveRecommendedJobs(source, boardToken, limit, maxAgeDays, query, company, true);
    }

    public Flux<CandidateJobSummaryResponse> getLiveRecommendedJobsForSync(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays) {
        return getLiveRecommendedJobs(source, boardToken, limit, maxAgeDays, null, null, false);
    }

    private Flux<CandidateJobSummaryResponse> getLiveRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company,
            boolean tolerateSourceFailures) {
        int resolvedLimit = normalizeLimit(limit);
        int resolvedMaxAgeDays = normalizeMaxAgeDays(maxAgeDays);
        List<Flux<CandidateJobSummaryResponse>> sourceStreams =
                recommendationSourceStreams(source, boardToken, resolvedLimit, tolerateSourceFailures);

        if (sourceStreams.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(sourceStreams)
                .flatMap(stream -> stream, liveFallbackSourceConcurrency)
                .filter(job -> isFresh(job, resolvedMaxAgeDays))
                .filter(this::isSupportedCountryJob)
                .filter(job -> matchesCompany(job, company))
                .filter(job -> matchesQuery(job, query))
                .collectList()
                .map(this::dedupeAndSort)
                .flatMapMany(jobs -> Flux.fromIterable(jobs).take(resolvedLimit));
    }

    private Flux<CandidateJobSummaryResponse> getLiveFallbackJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company) {
        if (!liveFallbackAllowed(source, boardToken)) {
            return Flux.empty();
        }
        return getLiveRecommendedJobs(source, boardToken, limit, maxAgeDays, query, company);
    }

    public Mono<CandidateJobDetailResponse> getExternalJobDetail(String sourceType, String boardToken, String externalJobId) {
        String normalizedSource = normalizeSource(sourceType);
        return externalJobPostingStore.findCachedJobDetail(normalizedSource, boardToken, externalJobId)
                .switchIfEmpty(externalJobPostingStore.existsActiveJob(normalizedSource, boardToken, externalJobId)
                        .flatMap(active -> {
                            if (!active) {
                                return Mono.error(new BadRequestException("Job detail is only available for active AIRRAL postings"));
                            }
                            return loadExternalJobDetail(normalizedSource, boardToken, externalJobId)
                                    .flatMap(externalJobPostingStore::attachCompanyBrand)
                                    .flatMap(detail -> externalJobPostingStore.cacheJobDetail(detail)
                                            .onErrorResume(error -> {
                                                log.warn("Unable to cache job detail {}:{}:{}: {}", normalizedSource, boardToken, externalJobId, error.getMessage());
                                                return Mono.just(0L);
                                            })
                                            .thenReturn(detail));
                        }));
    }

    public Mono<CandidateJobDetailResponse> getExternalJobDetail(
            String sourceType,
            String boardToken,
            String externalJobId,
            String candidateEmail) {
        return getExternalJobDetail(sourceType, boardToken, externalJobId)
                .flatMap(detail -> personalizeDetail(candidateEmail, detail));
    }

    private Mono<CandidateJobDetailResponse> loadExternalJobDetail(String normalizedSource, String boardToken, String externalJobId) {
        return switch (normalizedSource) {
            case "GREENHOUSE" -> getGreenhouseJobDetail(boardToken, parseGreenhouseJobId(externalJobId));
            case "LEVER" -> getLeverJobDetail(boardToken, externalJobId);
            case "ASHBY" -> getAshbyJobDetail(boardToken, externalJobId);
            case "SMARTRECRUITERS" -> getSmartRecruitersJobDetail(boardToken, externalJobId);
            case "WORKABLE" -> getWorkableJobDetail(boardToken, externalJobId);
            case "WORKDAY" -> getWorkdayJobDetail(boardToken, externalJobId);
            case "BAMBOOHR" -> getBambooHrJobDetail(boardToken, externalJobId);
            case "JOBVITE", "ICIMS", "JAZZHR" -> getCareerPageJobDetail(normalizedSource, boardToken, externalJobId);
            default -> Mono.error(new BadRequestException("Unsupported job source: " + normalizedSource));
        };
    }

    public Mono<CandidateJobDetailResponse> getGreenhouseJobDetail(String boardToken, Long jobId) {
        String resolvedBoard = resolveBoardToken(boardToken);

        if (jobId == null || jobId <= 0) {
            return Mono.error(new BadRequestException("Greenhouse job id is required"));
        }

        return greenhouseClient.retrieveJob(resolvedBoard, jobId)
                .timeout(Duration.ofSeconds(8))
                .map(job -> toGreenhouseDetail(resolvedBoard, job));
    }

    private List<Flux<CandidateJobSummaryResponse>> recommendationSourceStreams(
            String source,
            String boardToken,
            int limit,
            boolean tolerateSourceFailures) {
        String normalizedSource = normalizeSource(source);
        List<Flux<CandidateJobSummaryResponse>> streams = new ArrayList<>();

        if ("ALL".equals(normalizedSource) || "GREENHOUSE".equals(normalizedSource)) {
            List<String> boards = boardToken == null || boardToken.isBlank()
                    ? configuredValues(greenhouseBoards, defaultGreenhouseBoard)
                    : List.of(boardToken);
            boards.forEach(board -> streams.add(sourceStream("Greenhouse " + board, greenhouseSummaries(board, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "LEVER".equals(normalizedSource)) {
            List<String> sites = "LEVER".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(leverSites, null);
            sites
                    .forEach(site -> streams.add(sourceStream("Lever " + site, leverSummaries(site, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "ASHBY".equals(normalizedSource)) {
            List<String> boards = "ASHBY".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(ashbyBoards, null);
            boards
                    .forEach(board -> streams.add(sourceStream("Ashby " + board, ashbySummaries(board, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "SMARTRECRUITERS".equals(normalizedSource)) {
            List<String> companies = "SMARTRECRUITERS".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(smartRecruitersCompanies, null);
            companies
                    .forEach(company -> streams.add(sourceStream("SmartRecruiters " + company, smartRecruitersSummaries(company, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "WORKABLE".equals(normalizedSource)) {
            List<String> accounts = "WORKABLE".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(workableAccounts, null);
            accounts
                    .forEach(account -> streams.add(sourceStream("Workable " + account, workableSummaries(account, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "WORKDAY".equals(normalizedSource)) {
            List<String> sources = "WORKDAY".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(workdaySources, null);
            sources
                    .forEach(workdaySource -> streams.add(sourceStream("Workday " + workdaySource, workdaySummaries(workdaySource, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "BAMBOOHR".equals(normalizedSource)) {
            List<String> companies = "BAMBOOHR".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(bambooHrCompanies, null);
            companies
                    .forEach(company -> streams.add(sourceStream("BambooHR " + company, bambooHrSummaries(company, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "JOBVITE".equals(normalizedSource)) {
            List<String> pages = "JOBVITE".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(jobvitePages, null);
            pages
                    .forEach(page -> streams.add(sourceStream("Jobvite " + page, careerPageSummaries("JOBVITE", "Jobvite", page, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "ICIMS".equals(normalizedSource)) {
            List<String> pages = "ICIMS".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(icimsPages, null);
            pages
                    .forEach(page -> streams.add(sourceStream("iCIMS " + page, careerPageSummaries("ICIMS", "iCIMS", page, limit), tolerateSourceFailures)));
        }

        if ("ALL".equals(normalizedSource) || "JAZZHR".equals(normalizedSource)) {
            List<String> pages = "JAZZHR".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(jazzHrPages, null);
            pages
                    .forEach(page -> streams.add(sourceStream("JazzHR " + page, careerPageSummaries("JAZZHR", "JazzHR", page, limit), tolerateSourceFailures)));
        }

        return streams;
    }

    private boolean liveFallbackAllowed(String source, String boardToken) {
        int sourceCount = countLiveFallbackSources(source, boardToken);
        if (sourceCount <= maxLiveFallbackSources) {
            return true;
        }

        log.info(
                "Skipping live fallback for source={} board={} because {} configured sources exceeds max {}",
                source,
                boardToken,
                sourceCount,
                maxLiveFallbackSources);
        return false;
    }

    private int countLiveFallbackSources(String source, String boardToken) {
        String normalizedSource = normalizeSource(source);
        if (boardToken != null && !boardToken.isBlank() && !"ALL".equals(normalizedSource)) {
            return 1;
        }

        int count = 0;
        if ("ALL".equals(normalizedSource) || "GREENHOUSE".equals(normalizedSource)) {
            count += configuredValues(greenhouseBoards, defaultGreenhouseBoard).size();
        }
        if ("ALL".equals(normalizedSource) || "LEVER".equals(normalizedSource)) {
            count += configuredValues(leverSites, null).size();
        }
        if ("ALL".equals(normalizedSource) || "ASHBY".equals(normalizedSource)) {
            count += configuredValues(ashbyBoards, null).size();
        }
        if ("ALL".equals(normalizedSource) || "SMARTRECRUITERS".equals(normalizedSource)) {
            count += configuredValues(smartRecruitersCompanies, null).size();
        }
        if ("ALL".equals(normalizedSource) || "WORKABLE".equals(normalizedSource)) {
            count += configuredValues(workableAccounts, null).size();
        }
        if ("ALL".equals(normalizedSource) || "WORKDAY".equals(normalizedSource)) {
            count += configuredValues(workdaySources, null).size();
        }
        if ("ALL".equals(normalizedSource) || "BAMBOOHR".equals(normalizedSource)) {
            count += configuredValues(bambooHrCompanies, null).size();
        }
        if ("ALL".equals(normalizedSource) || "JOBVITE".equals(normalizedSource)) {
            count += configuredValues(jobvitePages, null).size();
        }
        if ("ALL".equals(normalizedSource) || "ICIMS".equals(normalizedSource)) {
            count += configuredValues(icimsPages, null).size();
        }
        if ("ALL".equals(normalizedSource) || "JAZZHR".equals(normalizedSource)) {
            count += configuredValues(jazzHrPages, null).size();
        }
        return count;
    }

    private Flux<CandidateJobSummaryResponse> sourceStream(
            String label,
            Flux<CandidateJobSummaryResponse> stream,
            boolean tolerateSourceFailures) {
        return tolerateSourceFailures ? safeSource(label, stream) : stream;
    }

    private Flux<CandidateJobSummaryResponse> safeSource(String label, Flux<CandidateJobSummaryResponse> stream) {
        return stream.onErrorResume(error -> {
            log.warn("Skipping external job source {} because it failed: {}", label, error.getMessage());
            return Flux.empty();
        });
    }

    private Flux<CandidateJobSummaryResponse> greenhouseSummaries(String boardToken, int limit) {
        String resolvedBoard = resolveBoardToken(boardToken);
        return greenhouseClient.listJobs(resolvedBoard)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(response -> Flux.fromIterable(response.getJobs() == null ? List.of() : response.getJobs()))
                .take(Math.max(limit * 2L, limit))
                .map(job -> toGreenhouseSummary(resolvedBoard, job));
    }

    private Flux<CandidateJobSummaryResponse> leverSummaries(String siteName, int limit) {
        String resolvedSite = siteName.trim();
        return leverClient.listJobs(resolvedSite, Math.max(limit * 2, limit))
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(postings -> Flux.fromIterable(postings == null ? List.of() : postings))
                .map(posting -> toLeverSummary(resolvedSite, posting));
    }

    private Flux<CandidateJobSummaryResponse> ashbySummaries(String boardName, int limit) {
        String resolvedBoard = boardName.trim();
        return ashbyClient.listJobs(resolvedBoard)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(response -> Flux.fromIterable(response.getJobs() == null ? List.of() : response.getJobs()))
                .filter(job -> job.getIsListed() == null || Boolean.TRUE.equals(job.getIsListed()))
                .take(Math.max(limit * 2L, limit))
                .map(job -> toAshbySummary(resolvedBoard, job));
    }

    private Flux<CandidateJobSummaryResponse> smartRecruitersSummaries(String companyIdentifier, int limit) {
        String resolvedCompany = companyIdentifier.trim();
        int pageSize = Math.min(100, Math.max(1, limit));
        int pages = Math.max(1, (limit + pageSize - 1) / pageSize);
        String country = "US".equalsIgnoreCase(supportedCountry) || "USA".equalsIgnoreCase(supportedCountry)
                ? "us"
                : null;

        return Flux.range(0, Math.min(pages, 5))
                .concatMap(page -> smartRecruitersClient.listJobs(resolvedCompany, pageSize, page * pageSize, country)
                        .timeout(Duration.ofSeconds(8))
                        .flatMapMany(response -> Flux.fromIterable(response.getContent() == null ? List.of() : response.getContent())))
                .filter(posting -> posting.getActive() == null || Boolean.TRUE.equals(posting.getActive()))
                .filter(posting -> posting.getVisibility() == null || "PUBLIC".equalsIgnoreCase(posting.getVisibility()))
                .take(limit)
                .map(posting -> toSmartRecruitersSummary(resolvedCompany, posting));
    }

    private Flux<CandidateJobSummaryResponse> workableSummaries(String account, int limit) {
        String resolvedAccount = account.trim();
        return workableClient.listJobs(resolvedAccount, true)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(response -> Flux.fromIterable(response.getJobs() == null ? List.of() : response.getJobs()))
                .take(Math.max(limit * 2L, limit))
                .map(job -> toWorkableSummary(resolvedAccount, job));
    }

    private Flux<CandidateJobSummaryResponse> workdaySummaries(String sourceToken, int limit) {
        WorkdayJobBoardClient.WorkdaySource source = WorkdayJobBoardClient.WorkdaySource.parse(sourceToken);
        int pageSize = Math.min(20, Math.max(1, limit));
        int pages = Math.max(1, (limit + pageSize - 1) / pageSize);

        return Flux.range(0, Math.min(pages, 25))
                .concatMap(page -> workdayClient.listJobs(source, pageSize, page * pageSize, "")
                        .timeout(Duration.ofSeconds(8))
                        .flatMapMany(response -> Flux.fromIterable(response.getJobPostings() == null ? List.of() : response.getJobPostings())))
                .filter(posting -> posting.getExternalPath() != null && !posting.getExternalPath().isBlank())
                .take(limit)
                .map(posting -> toWorkdaySummary(source, posting));
    }

    private Flux<CandidateJobSummaryResponse> bambooHrSummaries(String companyDomain, int limit) {
        String resolvedCompany = companyDomain.trim();
        return bambooHrClient.listJobs(resolvedCompany)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(jobs -> Flux.fromIterable(jobs == null ? List.of() : jobs))
                .filter(job -> job.getPostingUrl() != null && !job.getPostingUrl().isBlank())
                .take(limit)
                .map(job -> toBambooHrSummary(resolvedCompany, job));
    }

    private Flux<CandidateJobSummaryResponse> careerPageSummaries(String sourceType, String sourceName, String pageUrl, int limit) {
        return careerPageClient.fetchPage(pageUrl, sourceName)
                .timeout(Duration.ofSeconds(8))
                .map(this::extractSchemaOrgJobs)
                .flatMapMany(Flux::fromIterable)
                .filter(job -> job.getTitle() != null && !job.getTitle().isBlank())
                .take(limit)
                .map(job -> toSchemaOrgSummary(sourceType, sourceName, pageUrl, job));
    }

    private Mono<CandidateJobDetailResponse> getLeverJobDetail(String siteName, String postingId) {
        if (postingId == null || postingId.isBlank()) {
            return Mono.error(new BadRequestException("Lever posting id is required"));
        }

        String resolvedSite = siteName == null || siteName.isBlank() ? "" : siteName.trim();
        return leverClient.retrieveJob(resolvedSite, postingId)
                .timeout(Duration.ofSeconds(8))
                .map(posting -> toLeverDetail(resolvedSite, posting));
    }

    private Mono<CandidateJobDetailResponse> getAshbyJobDetail(String boardName, String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return Mono.error(new BadRequestException("Ashby job id is required"));
        }

        String resolvedBoard = boardName == null || boardName.isBlank() ? "" : boardName.trim();
        return ashbyClient.listJobs(resolvedBoard)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(response -> Flux.fromIterable(response.getJobs() == null ? List.of() : response.getJobs()))
                .filter(job -> externalJobId.equals(ashbyExternalJobId(job)))
                .next()
                .switchIfEmpty(Mono.error(new BadRequestException("Unable to find Ashby job " + externalJobId + " for board: " + boardName)))
                .map(job -> toAshbyDetail(resolvedBoard, job));
    }

    private Mono<CandidateJobDetailResponse> getSmartRecruitersJobDetail(String companyIdentifier, String postingId) {
        if (postingId == null || postingId.isBlank()) {
            return Mono.error(new BadRequestException("SmartRecruiters posting id is required"));
        }

        String resolvedCompany = companyIdentifier == null || companyIdentifier.isBlank() ? "" : companyIdentifier.trim();
        return smartRecruitersClient.retrieveJob(resolvedCompany, postingId)
                .timeout(Duration.ofSeconds(8))
                .map(posting -> toSmartRecruitersDetail(resolvedCompany, posting));
    }

    private Mono<CandidateJobDetailResponse> getWorkableJobDetail(String account, String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return Mono.error(new BadRequestException("Workable job id is required"));
        }

        String resolvedAccount = account == null || account.isBlank() ? "" : account.trim();
        return workableClient.listJobs(resolvedAccount, true)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(response -> Flux.fromIterable(response.getJobs() == null ? List.of() : response.getJobs()))
                .filter(job -> externalJobId.equals(workableExternalJobId(job)))
                .next()
                .switchIfEmpty(Mono.error(new BadRequestException("Unable to find Workable job " + externalJobId + " for account: " + account)))
                .map(job -> toWorkableDetail(resolvedAccount, job));
    }

    private Mono<CandidateJobDetailResponse> getWorkdayJobDetail(String sourceToken, String encodedExternalPath) {
        if (encodedExternalPath == null || encodedExternalPath.isBlank()) {
            return Mono.error(new BadRequestException("Workday external path is required"));
        }

        WorkdayJobBoardClient.WorkdaySource source = WorkdayJobBoardClient.WorkdaySource.parse(sourceToken);
        String externalPath = decodeJobId(encodedExternalPath);
        return workdayClient.retrieveJob(source, externalPath)
                .timeout(Duration.ofSeconds(8))
                .map(detail -> toWorkdayDetail(source, externalPath, detail));
    }

    private Mono<CandidateJobDetailResponse> getBambooHrJobDetail(String companyDomain, String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return Mono.error(new BadRequestException("BambooHR job id is required"));
        }

        String resolvedCompany = companyDomain == null || companyDomain.isBlank() ? "" : companyDomain.trim();
        return bambooHrClient.listJobs(resolvedCompany)
                .timeout(Duration.ofSeconds(8))
                .flatMapMany(jobs -> Flux.fromIterable(jobs == null ? List.of() : jobs))
                .filter(job -> externalJobId.equals(String.valueOf(job.getId())))
                .next()
                .switchIfEmpty(Mono.error(new BadRequestException("Unable to find BambooHR job " + externalJobId + " for company: " + companyDomain)))
                .map(job -> toBambooHrDetail(resolvedCompany, job));
    }

    private Mono<CandidateJobDetailResponse> getCareerPageJobDetail(String sourceType, String pageUrl, String externalJobId) {
        String sourceName = sourceDisplayName(sourceType);
        String resolvedPageUrl = pageUrl.startsWith("http") ? pageUrl : decodeJobId(pageUrl);
        return careerPageClient.fetchPage(resolvedPageUrl, sourceName)
                .timeout(Duration.ofSeconds(8))
                .map(this::extractSchemaOrgJobs)
                .flatMapMany(Flux::fromIterable)
                .filter(job -> externalJobId.equals(schemaOrgExternalJobId(job)))
                .next()
                .switchIfEmpty(Mono.error(new BadRequestException("Unable to find " + sourceName + " job " + externalJobId + " for page: " + resolvedPageUrl)))
                .map(job -> toSchemaOrgDetail(sourceType, sourceName, resolvedPageUrl, job));
    }

    private CandidateJobSummaryResponse toGreenhouseSummary(String boardToken, GreenhouseJobBoardResponse.GreenhouseJob job) {
        String location = locationName(job);

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("GREENHOUSE", boardToken, String.valueOf(job.getId())))
                .sourceType("GREENHOUSE")
                .sourceName("Greenhouse")
                .sourceBoardToken(boardToken)
                .externalJobId(String.valueOf(job.getId()))
                .title(job.getTitle())
                .companyName(formatCompanyName(boardToken))
                .department(firstDepartment(job))
                .location(location)
                .workMode(inferWorkMode(job.getTitle(), location))
                .employmentType("Full-time")
                .salaryLabel("Salary not listed")
                .applyUrl(job.getAbsoluteUrl())
                .jobUrl(job.getAbsoluteUrl())
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(job.getUpdatedAt())
                .postedLabel(formatPostedLabel(job.getUpdatedAt()))
                .matchScore(inferMatchScore(job.getTitle(), firstDepartment(job)))
                .connectionsCount(0)
                .tags(buildTags(job.getTitle(), firstDepartment(job), inferWorkMode(job.getTitle(), location), List.of()))
                .build());
    }

    private CandidateJobDetailResponse toGreenhouseDetail(String boardToken, GreenhouseJobBoardResponse.GreenhouseJob job) {
        String location = locationName(job);
        String descriptionText = stripHtml(job.getContent());
        GreenhouseJobBoardResponse.GreenhousePayRange payRange = firstPayRange(job);

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(sourceJobId("GREENHOUSE", boardToken, String.valueOf(job.getId())))
                .sourceType("GREENHOUSE")
                .sourceName("Greenhouse")
                .sourceBoardToken(boardToken)
                .externalJobId(String.valueOf(job.getId()))
                .externalInternalJobId(job.getInternalJobId() == null ? null : String.valueOf(job.getInternalJobId()))
                .title(job.getTitle())
                .companyName(formatCompanyName(boardToken))
                .department(firstDepartment(job))
                .location(location)
                .workMode(inferWorkMode(job.getTitle(), location))
                .employmentType("Full-time")
                .descriptionHtml(job.getContent())
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryMin(payRange == null ? null : centsToUnits(payRange.getMinCents()))
                .salaryMax(payRange == null ? null : centsToUnits(payRange.getMaxCents()))
                .salaryCurrency(payRange == null ? null : payRange.getCurrencyType())
                .salaryLabel(payRange == null ? "Salary not listed" : formatSalary(payRange))
                .applyUrl(job.getAbsoluteUrl())
                .jobUrl(job.getAbsoluteUrl())
                .applyMode("EXTERNAL_APPLY")
                .sourceUpdatedAt(job.getUpdatedAt())
                .postedLabel(formatPostedLabel(job.getUpdatedAt()))
                .matchScore(inferMatchScore(job.getTitle(), firstDepartment(job)))
                .connectionsCount(0)
                .tags(buildTags(job.getTitle(), firstDepartment(job), inferWorkMode(job.getTitle(), location), officeNames(job)))
                .sourcePayloadHash(hash(job.getId() + "|" + job.getUpdatedAt() + "|" + job.getAbsoluteUrl()))
                .build());
    }

    private CandidateJobSummaryResponse toLeverSummary(String siteName, LeverPostingResponse posting) {
        LeverPostingResponse.LeverCategories categories = posting.getCategories();
        String location = appendCountryIfUseful(firstNonBlank(
                categories == null ? null : categories.getLocation(),
                firstListValue(categories == null ? null : categories.getAllLocations()),
                "Location not listed"), posting.getCountry());
        OffsetDateTime sourceDate = epochMillisToOffsetDateTime(firstNonNull(posting.getUpdatedAt(), posting.getCreatedAt()));
        String workMode = formatLeverWorkMode(posting.getWorkplaceType(), location);
        String department = firstNonBlank(
                categories == null ? null : categories.getDepartment(),
                categories == null ? null : categories.getTeam());

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("LEVER", siteName, posting.getId()))
                .sourceType("LEVER")
                .sourceName("Lever")
                .sourceBoardToken(siteName)
                .externalJobId(posting.getId())
                .title(posting.getText())
                .companyName(formatCompanyName(siteName))
                .department(department)
                .location(location)
                .workMode(workMode)
                .employmentType(categories == null ? null : categories.getCommitment())
                .salaryLabel(formatLeverSalary(posting))
                .applyUrl(posting.getApplyUrl())
                .jobUrl(posting.getHostedUrl())
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(sourceDate)
                .postedLabel(formatPostedLabel(sourceDate))
                .matchScore(inferMatchScore(posting.getText(), department))
                .connectionsCount(0)
                .tags(buildTags(posting.getText(), department, workMode, List.of()))
                .build());
    }

    private CandidateJobDetailResponse toLeverDetail(String siteName, LeverPostingResponse posting) {
        CandidateJobSummaryResponse summary = toLeverSummary(siteName, posting);
        String descriptionText = firstNonBlank(
                posting.getDescriptionPlain(),
                posting.getOpeningPlain(),
                stripHtml(posting.getDescription()),
                stripHtml(posting.getOpening()));

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .department(summary.getDepartment())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionHtml(firstNonBlank(posting.getDescription(), posting.getOpening()))
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryMin(posting.getSalaryRange() == null ? null : posting.getSalaryRange().getMin())
                .salaryMax(posting.getSalaryRange() == null ? null : posting.getSalaryRange().getMax())
                .salaryCurrency(posting.getSalaryRange() == null ? null : posting.getSalaryRange().getCurrency())
                .salaryLabel(summary.getSalaryLabel())
                .applyUrl(summary.getApplyUrl())
                .jobUrl(summary.getJobUrl())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .matchScore(summary.getMatchScore())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(hash(posting.getId() + "|" + posting.getUpdatedAt() + "|" + posting.getHostedUrl()))
                .build());
    }

    private CandidateJobSummaryResponse toAshbySummary(String boardName, AshbyJobBoardResponse.AshbyJob job) {
        String country = ashbyCountry(job);
        String location = appendCountryIfUseful(firstNonBlank(job.getLocation(), ashbyAddressLabel(job), "Location not listed"), country);
        String workMode = formatAshbyWorkMode(job, location);

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("ASHBY", boardName, ashbyExternalJobId(job)))
                .sourceType("ASHBY")
                .sourceName("Ashby")
                .sourceBoardToken(boardName)
                .externalJobId(ashbyExternalJobId(job))
                .title(job.getTitle())
                .companyName(formatCompanyName(boardName))
                .department(firstNonBlank(job.getDepartment(), job.getTeam()))
                .location(location)
                .workMode(workMode)
                .employmentType(job.getEmploymentType())
                .salaryLabel(formatAshbySalary(job))
                .applyUrl(job.getApplyUrl())
                .jobUrl(job.getJobUrl())
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(job.getPublishedAt())
                .postedLabel(formatPostedLabel(job.getPublishedAt()))
                .matchScore(inferMatchScore(job.getTitle(), firstNonBlank(job.getDepartment(), job.getTeam())))
                .connectionsCount(0)
                .tags(buildTags(job.getTitle(), firstNonBlank(job.getDepartment(), job.getTeam()), workMode, List.of()))
                .build());
    }

    private CandidateJobDetailResponse toAshbyDetail(String boardName, AshbyJobBoardResponse.AshbyJob job) {
        CandidateJobSummaryResponse summary = toAshbySummary(boardName, job);
        AshbyJobBoardResponse.AshbyCompensationComponent salaryComponent = ashbySalaryComponent(job);
        String descriptionText = firstNonBlank(job.getDescriptionPlain(), stripHtml(job.getDescriptionHtml()));

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .department(summary.getDepartment())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionHtml(job.getDescriptionHtml())
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryMin(salaryComponent == null ? null : salaryComponent.getMinValue())
                .salaryMax(salaryComponent == null ? null : salaryComponent.getMaxValue())
                .salaryCurrency(salaryComponent == null ? null : salaryComponent.getCurrencyCode())
                .salaryLabel(summary.getSalaryLabel())
                .applyUrl(summary.getApplyUrl())
                .jobUrl(summary.getJobUrl())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .matchScore(summary.getMatchScore())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(hash(summary.getExternalJobId() + "|" + job.getPublishedAt() + "|" + job.getJobUrl()))
                .build());
    }

    private CandidateJobSummaryResponse toSmartRecruitersSummary(String companyIdentifier, SmartRecruitersPostingResponse.Posting posting) {
        String companyName = posting.getCompany() == null
                ? formatCompanyName(companyIdentifier)
                : firstNonBlank(posting.getCompany().getName(), formatCompanyName(companyIdentifier));
        String location = smartRecruitersLocation(posting);
        String department = smartRecruitersDepartment(posting);
        String workMode = smartRecruitersWorkMode(posting, location);
        String applyUrl = firstNonBlank(posting.getApplyUrl(), posting.getPostingUrl(), smartRecruitersHostedUrl(companyIdentifier, posting));

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("SMARTRECRUITERS", companyIdentifier, posting.getId()))
                .sourceType("SMARTRECRUITERS")
                .sourceName("SmartRecruiters")
                .sourceBoardToken(companyIdentifier)
                .externalJobId(posting.getId())
                .title(posting.getName())
                .companyName(companyName)
                .department(department)
                .location(location)
                .workMode(workMode)
                .employmentType(label(posting.getTypeOfEmployment()))
                .salaryLabel("Salary not listed")
                .applyUrl(applyUrl)
                .jobUrl(firstNonBlank(posting.getPostingUrl(), applyUrl))
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(posting.getReleasedDate())
                .postedLabel(formatPostedLabel(posting.getReleasedDate()))
                .matchScore(inferMatchScore(posting.getName(), department))
                .connectionsCount(0)
                .tags(buildTags(posting.getName(), department, workMode, smartRecruitersExtraTags(posting)))
                .build());
    }

    private CandidateJobDetailResponse toSmartRecruitersDetail(String companyIdentifier, SmartRecruitersPostingResponse.Posting posting) {
        CandidateJobSummaryResponse summary = toSmartRecruitersSummary(companyIdentifier, posting);
        String descriptionHtml = smartRecruitersDescriptionHtml(posting);
        String descriptionText = stripHtml(descriptionHtml);
        String salaryLabel = firstNonBlank(extractSalaryLabel(descriptionText), summary.getSalaryLabel());

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .externalInternalJobId(firstNonBlank(posting.getJobId(), posting.getRefNumber()))
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .department(summary.getDepartment())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionHtml(descriptionHtml)
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryLabel(salaryLabel)
                .applyUrl(summary.getApplyUrl())
                .jobUrl(summary.getJobUrl())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .matchScore(summary.getMatchScore())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(hash(posting.getId() + "|" + posting.getReleasedDate() + "|" + summary.getJobUrl()))
                .build());
    }

    private CandidateJobSummaryResponse toWorkableSummary(String account, WorkableJobBoardResponse.WorkableJob job) {
        String location = workableLocation(job);
        String workMode = workableWorkMode(job, location);
        String descriptionText = stripHtml(job.getDescription());
        String applyUrl = firstNonBlank(job.getApplicationUrl(), job.getUrl(), job.getShortlink());
        String jobUrl = firstNonBlank(job.getUrl(), job.getShortlink(), applyUrl);
        OffsetDateTime sourceDate = parseOffsetDate(firstNonBlank(job.getPublishedOn(), job.getCreatedAt()));
        String salaryLabel = firstNonBlank(extractSalaryLabel(descriptionText), "Salary not listed");

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("WORKABLE", account, workableExternalJobId(job)))
                .sourceType("WORKABLE")
                .sourceName("Workable")
                .sourceBoardToken(account)
                .externalJobId(workableExternalJobId(job))
                .title(job.getTitle())
                .companyName(formatCompanyName(account))
                .department(firstNonBlank(job.getDepartment(), job.getFunction(), job.getIndustry()))
                .location(location)
                .workMode(workMode)
                .employmentType(job.getEmploymentType())
                .salaryLabel(salaryLabel)
                .applyUrl(applyUrl)
                .jobUrl(jobUrl)
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(sourceDate)
                .postedLabel(formatPostedLabel(sourceDate))
                .matchScore(inferMatchScore(job.getTitle(), firstNonBlank(job.getDepartment(), job.getFunction())))
                .connectionsCount(0)
                .tags(buildTags(job.getTitle(), firstNonBlank(job.getDepartment(), job.getFunction()), workMode, compactList(job.getIndustry(), job.getExperience(), job.getEducation())))
                .build());
    }

    private CandidateJobDetailResponse toWorkableDetail(String account, WorkableJobBoardResponse.WorkableJob job) {
        CandidateJobSummaryResponse summary = toWorkableSummary(account, job);
        String descriptionText = stripHtml(job.getDescription());

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .externalInternalJobId(firstNonBlank(job.getCode(), job.getShortcode()))
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .department(summary.getDepartment())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionHtml(job.getDescription())
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryLabel(summary.getSalaryLabel())
                .applyUrl(summary.getApplyUrl())
                .jobUrl(summary.getJobUrl())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .matchScore(summary.getMatchScore())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(hash(workableExternalJobId(job) + "|" + summary.getSourceUpdatedAt() + "|" + summary.getJobUrl()))
                .build());
    }

    private CandidateJobSummaryResponse toWorkdaySummary(WorkdayJobBoardClient.WorkdaySource source, WorkdayJobSearchResponse.WorkdayJobPosting posting) {
        String location = firstNonBlank(posting.getLocationsText(), "Location not listed");
        String workMode = workdayWorkMode(posting.getRemoteType(), location);
        OffsetDateTime sourceDate = parseRelativePostedLabel(posting.getPostedOn());
        String externalJobId = workdayExternalJobId(posting.getExternalPath());
        String url = source.publicUrl(posting.getExternalPath());

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("WORKDAY", source.sourceKey(), externalJobId))
                .sourceType("WORKDAY")
                .sourceName("Workday")
                .sourceBoardToken(source.sourceKey())
                .externalJobId(externalJobId)
                .title(posting.getTitle())
                .companyName(formatCompanyName(source.site()))
                .department(firstListValue(posting.getBulletFields()))
                .location(location)
                .workMode(workMode)
                .employmentType(null)
                .salaryLabel("Salary not listed")
                .applyUrl(url)
                .jobUrl(url)
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(sourceDate)
                .postedLabel(formatPostedLabel(sourceDate))
                .matchScore(inferMatchScore(posting.getTitle(), firstListValue(posting.getBulletFields())))
                .connectionsCount(0)
                .tags(buildTags(posting.getTitle(), firstListValue(posting.getBulletFields()), workMode, posting.getBulletFields()))
                .build());
    }

    private CandidateJobDetailResponse toWorkdayDetail(
            WorkdayJobBoardClient.WorkdaySource source,
            String externalPath,
            WorkdayJobDetailResponse detail) {
        WorkdayJobDetailResponse.WorkdayJobPostingInfo info = detail.getJobPostingInfo();
        if (info == null) {
            throw new BadRequestException("Workday job detail response did not include jobPostingInfo");
        }

        String descriptionHtml = info.getJobDescription();
        String descriptionText = stripHtml(descriptionHtml);
        String location = appendCountryIfUseful(
                firstNonBlank(info.getLocation(), "Location not listed"),
                info.getCountry() == null ? null : info.getCountry().getDescriptor());
        String workMode = workdayWorkMode(info.getRemoteType(), location);
        OffsetDateTime sourceDate = firstNonNull(parseOffsetDate(info.getStartDate()), parseRelativePostedLabel(info.getPostedOn()));
        String externalJobId = workdayExternalJobId(externalPath);
        String url = firstNonBlank(info.getExternalUrl(), source.publicUrl(externalPath));
        String salaryLabel = firstNonBlank(extractSalaryLabel(descriptionText), "Salary not listed");

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(sourceJobId("WORKDAY", source.sourceKey(), externalJobId))
                .sourceType("WORKDAY")
                .sourceName("Workday")
                .sourceBoardToken(source.sourceKey())
                .externalJobId(externalJobId)
                .externalInternalJobId(firstNonBlank(info.getJobReqId(), info.getJobPostingId(), info.getId()))
                .title(info.getTitle())
                .companyName(formatCompanyName(source.site()))
                .location(location)
                .workMode(workMode)
                .employmentType(info.getTimeType())
                .descriptionHtml(descriptionHtml)
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryLabel(salaryLabel)
                .applyUrl(url)
                .jobUrl(url)
                .applyMode("EXTERNAL_APPLY")
                .sourceUpdatedAt(sourceDate)
                .postedLabel(formatPostedLabel(sourceDate))
                .matchScore(inferMatchScore(info.getTitle(), null))
                .connectionsCount(0)
                .tags(buildTags(info.getTitle(), null, workMode, compactList(info.getTimeType(), info.getJobReqId())))
                .sourcePayloadHash(hash(externalPath + "|" + info.getStartDate() + "|" + url))
                .build());
    }

    private CandidateJobSummaryResponse toBambooHrSummary(String companyDomain, BambooHrJobSummaryResponse job) {
        String title = bambooHrLabel(job.getTitle());
        String department = bambooHrLabel(job.getDepartment());
        String location = firstNonBlank(bambooHrLabel(job.getLocation()), "Location not listed");

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId("BAMBOOHR", companyDomain, String.valueOf(job.getId())))
                .sourceType("BAMBOOHR")
                .sourceName("BambooHR")
                .sourceBoardToken(companyDomain)
                .externalJobId(String.valueOf(job.getId()))
                .title(title)
                .companyName(formatCompanyName(companyDomain.replace(".bamboohr.com", "")))
                .department(department)
                .location(location)
                .workMode(inferWorkMode(title, location))
                .employmentType("Full-time")
                .salaryLabel("Salary not listed")
                .applyUrl(job.getPostingUrl())
                .jobUrl(job.getPostingUrl())
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(job.getPostedDate())
                .postedLabel(formatPostedLabel(job.getPostedDate()))
                .matchScore(inferMatchScore(title, department))
                .connectionsCount(0)
                .tags(buildTags(title, department, inferWorkMode(title, location), compactList(bambooHrLabel(job.getStatus()))))
                .build());
    }

    private CandidateJobDetailResponse toBambooHrDetail(String companyDomain, BambooHrJobSummaryResponse job) {
        CandidateJobSummaryResponse summary = toBambooHrSummary(companyDomain, job);
        String descriptionText = "Open this official BambooHR posting to review the full job description and apply.";

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .department(summary.getDepartment())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryLabel(summary.getSalaryLabel())
                .applyUrl(summary.getApplyUrl())
                .jobUrl(summary.getJobUrl())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .matchScore(summary.getMatchScore())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(hash(summary.getExternalJobId() + "|" + summary.getSourceUpdatedAt() + "|" + summary.getJobUrl()))
                .build());
    }

    private CandidateJobSummaryResponse toSchemaOrgSummary(String sourceType, String sourceName, String pageUrl, SchemaOrgJobPosting job) {
        String pageToken = encodeJobId(pageUrl);
        String externalJobId = schemaOrgExternalJobId(job);
        String descriptionText = stripHtml(job.getDescription());
        String companyName = firstNonBlank(schemaOrgOrganizationName(job.getHiringOrganization()), companyNameFromUrl(pageUrl), sourceName);
        String location = firstNonBlank(schemaOrgLocation(job.getJobLocation()), "Location not listed");
        String employmentType = schemaValue(job.getEmploymentType());
        String workMode = inferWorkMode(job.getTitle(), location);
        OffsetDateTime sourceDate = parseOffsetDate(schemaValue(job.getDatePosted()));
        String url = firstNonBlank(job.getUrl(), pageUrl);

        return withDecisionSignals(CandidateJobSummaryResponse.builder()
                .jobId(sourceJobId(sourceType, pageToken, externalJobId))
                .sourceType(sourceType)
                .sourceName(sourceName)
                .sourceBoardToken(pageToken)
                .externalJobId(externalJobId)
                .title(job.getTitle())
                .companyName(companyName)
                .location(location)
                .workMode(workMode)
                .employmentType(employmentType)
                .salaryLabel(firstNonBlank(extractSalaryLabel(descriptionText), "Salary not listed"))
                .applyUrl(url)
                .jobUrl(url)
                .applyMode("EXTERNAL_APPLY")
                .easyApplyAvailable(false)
                .sourceUpdatedAt(sourceDate)
                .postedLabel(formatPostedLabel(sourceDate))
                .matchScore(inferMatchScore(job.getTitle(), null))
                .connectionsCount(0)
                .tags(buildTags(job.getTitle(), null, workMode, compactList(employmentType, sourceName)))
                .build());
    }

    private CandidateJobDetailResponse toSchemaOrgDetail(String sourceType, String sourceName, String pageUrl, SchemaOrgJobPosting job) {
        CandidateJobSummaryResponse summary = toSchemaOrgSummary(sourceType, sourceName, pageUrl, job);
        String descriptionText = stripHtml(job.getDescription());

        return withDecisionSignals(CandidateJobDetailResponse.builder()
                .jobId(summary.getJobId())
                .sourceType(summary.getSourceType())
                .sourceName(summary.getSourceName())
                .sourceBoardToken(summary.getSourceBoardToken())
                .externalJobId(summary.getExternalJobId())
                .title(summary.getTitle())
                .companyName(summary.getCompanyName())
                .location(summary.getLocation())
                .workMode(summary.getWorkMode())
                .employmentType(summary.getEmploymentType())
                .descriptionHtml(job.getDescription())
                .descriptionText(descriptionText)
                .descriptionExcerpt(excerpt(descriptionText))
                .salaryLabel(summary.getSalaryLabel())
                .applyUrl(summary.getApplyUrl())
                .jobUrl(summary.getJobUrl())
                .applyMode(summary.getApplyMode())
                .sourceUpdatedAt(summary.getSourceUpdatedAt())
                .postedLabel(summary.getPostedLabel())
                .matchScore(summary.getMatchScore())
                .connectionsCount(summary.getConnectionsCount())
                .tags(summary.getTags())
                .sourcePayloadHash(hash(schemaOrgExternalJobId(job) + "|" + schemaValue(job.getDatePosted()) + "|" + summary.getJobUrl()))
                .build());
    }

    private CandidateJobSummaryResponse withDecisionSignals(CandidateJobSummaryResponse job) {
        job.setJobQualityScore(firstNonNull(job.getJobQualityScore(), inferJobQualityScore(
                job.getSalaryLabel(),
                job.getLocation(),
                job.getSourceUpdatedAt(),
                job.getApplyUrl(),
                job.getJobUrl(),
                job.getDepartment(),
                job.getWorkMode(),
                null)));
        job.setQualityReasons(firstNonNull(job.getQualityReasons(), buildQualityReasons(
                job.getSalaryLabel(),
                job.getLocation(),
                job.getSourceUpdatedAt(),
                job.getApplyUrl(),
                job.getJobUrl(),
                job.getDepartment(),
                null)));
        job.setTotalCompLabel(firstNonBlank(job.getTotalCompLabel(), inferTotalCompLabel(job.getSalaryLabel())));
        job.setCompensationConfidence(firstNonBlank(job.getCompensationConfidence(), inferCompensationConfidence(job.getSalaryLabel())));
        applyVisaSignals(job, null);
        applyExperienceSignals(job, null);
        return job;
    }

    private CandidateJobDetailResponse withDecisionSignals(CandidateJobDetailResponse detail) {
        detail.setJobQualityScore(firstNonNull(detail.getJobQualityScore(), inferJobQualityScore(
                detail.getSalaryLabel(),
                detail.getLocation(),
                detail.getSourceUpdatedAt(),
                detail.getApplyUrl(),
                detail.getJobUrl(),
                detail.getDepartment(),
                detail.getWorkMode(),
                detail.getDescriptionText())));
        detail.setQualityReasons(firstNonNull(detail.getQualityReasons(), buildQualityReasons(
                detail.getSalaryLabel(),
                detail.getLocation(),
                detail.getSourceUpdatedAt(),
                detail.getApplyUrl(),
                detail.getJobUrl(),
                detail.getDepartment(),
                detail.getDescriptionText())));
        detail.setTotalCompLabel(firstNonBlank(detail.getTotalCompLabel(), inferTotalCompLabel(detail.getSalaryLabel())));
        detail.setCompensationConfidence(firstNonBlank(detail.getCompensationConfidence(), inferCompensationConfidence(detail.getSalaryLabel())));
        applyVisaSignals(detail, detail.getDescriptionText());
        applyExperienceSignals(detail, detail.getDescriptionText());
        return detail;
    }

    private void applyExperienceSignals(CandidateJobSummaryResponse job, String descriptionText) {
        if (job.getSeniorityLabel() != null && job.getExperienceYears() != null) {
            return; // Already set from DB
        }
        ExperienceSignal signal = inferExperienceSignal(job.getTitle(), descriptionText);
        if (job.getSeniorityLabel() == null) {
            job.setSeniorityLabel(signal.seniorityLabel());
        }
        if (job.getExperienceYears() == null) {
            job.setExperienceYears(signal.experienceYears());
        }
    }

    private void applyExperienceSignals(CandidateJobDetailResponse detail, String descriptionText) {
        if (detail.getSeniorityLabel() != null && detail.getExperienceYears() != null) {
            return; // Already set from DB
        }
        ExperienceSignal signal = inferExperienceSignal(detail.getTitle(), descriptionText);
        if (detail.getSeniorityLabel() == null) {
            detail.setSeniorityLabel(signal.seniorityLabel());
        }
        if (detail.getExperienceYears() == null) {
            detail.setExperienceYears(signal.experienceYears());
        }
    }

    private ExperienceSignal inferExperienceSignal(String title, String descriptionText) {
        // 1. Try to extract explicit years from description (e.g., "3+ years", "5-7 years experience")
        Integer yearsFromDesc = extractYearsFromText(descriptionText);

        // 2. Infer seniority from title
        String titleLower = title == null ? "" : title.toLowerCase(Locale.US);
        String seniorityLabel = inferSeniorityFromTitle(titleLower);

        // 3. If no explicit years from description, infer from title seniority
        Integer years = yearsFromDesc;
        if (years == null) {
            years = inferYearsFromSeniority(seniorityLabel);
        }

        // 4. If we got years from description but no seniority label, derive label from years
        if (seniorityLabel == null && years != null) {
            seniorityLabel = labelFromYears(years);
        }

        return new ExperienceSignal(seniorityLabel, years);
    }

    private static final java.util.regex.Pattern YEARS_EXPERIENCE_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(\\d{1,2})\\s*(?:\\+|\\-\\s*\\d{1,2})?\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:experience|exp|professional|relevant|work|industry|related|minimum|min)?"
    );

    private Integer extractYearsFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        java.util.regex.Matcher matcher = YEARS_EXPERIENCE_PATTERN.matcher(text);
        Integer minYears = null;
        while (matcher.find()) {
            try {
                int found = Integer.parseInt(matcher.group(1));
                if (found >= 0 && found <= 30) {
                    if (minYears == null || found < minYears) {
                        minYears = found;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return minYears;
    }

    private String inferSeniorityFromTitle(String titleLower) {
        if (titleLower.contains("intern") || titleLower.contains("internship")) {
            return "Intern";
        }
        if (titleLower.contains("entry level") || titleLower.contains("entry-level")
                || titleLower.contains("new grad") || titleLower.contains("associate")
                || titleLower.endsWith(" i") || titleLower.contains(" i ")) {
            return "Entry";
        }
        if (titleLower.contains("junior") || titleLower.contains(" jr")) {
            return "Entry";
        }
        if (titleLower.contains("director") || titleLower.contains("vp ")
                || titleLower.contains("vice president") || titleLower.contains("chief")
                || titleLower.contains("head of")) {
            return "Director+";
        }
        if (titleLower.contains("principal") || titleLower.contains("distinguished")) {
            return "Staff+";
        }
        if (titleLower.contains("staff")) {
            return "Staff+";
        }
        if (titleLower.contains("lead") || titleLower.contains("tech lead")
                || titleLower.contains("team lead")) {
            return "Lead";
        }
        if (titleLower.contains("senior") || titleLower.contains(" sr")) {
            return "Senior";
        }
        if (titleLower.endsWith(" ii") || titleLower.contains(" ii ")
                || titleLower.endsWith(" iii") || titleLower.contains(" iii ")) {
            return "Mid";
        }
        return null;
    }

    private Integer inferYearsFromSeniority(String seniorityLabel) {
        if (seniorityLabel == null) {
            return null;
        }
        return switch (seniorityLabel) {
            case "Intern" -> 0;
            case "Entry" -> 0;
            case "Mid" -> 2;
            case "Senior" -> 5;
            case "Lead" -> 6;
            case "Staff+" -> 8;
            case "Director+" -> 10;
            default -> null;
        };
    }

    private String labelFromYears(int years) {
        if (years <= 0) return "Entry";
        if (years <= 2) return "Mid";
        if (years <= 5) return "Senior";
        if (years <= 7) return "Lead";
        if (years <= 10) return "Staff+";
        return "Director+";
    }

    private record ExperienceSignal(String seniorityLabel, Integer experienceYears) {}

    private void applyVisaSignals(CandidateJobSummaryResponse job, String descriptionText) {
        VisaSignal signal = inferVisaSignal(job, descriptionText);
        job.setSponsorshipLanguage(firstNonBlank(job.getSponsorshipLanguage(), signal.sponsorshipLanguage()));
        job.setVisaConfidenceScore(firstNonNull(job.getVisaConfidenceScore(), signal.visaConfidenceScore()));
        job.setVisaReasons(firstNonNull(job.getVisaReasons(), signal.reasons()));
        job.setRequiresUsWorkAuthorization(firstNonNull(job.getRequiresUsWorkAuthorization(), signal.requiresUsWorkAuthorization()));
        job.setContractOrStaffingRisk(firstNonNull(job.getContractOrStaffingRisk(), signal.contractOrStaffingRisk()));
        job.setStemOptRisk(firstNonNull(job.getStemOptRisk(), signal.stemOptRisk()));
        job.setH1bTransferFit(firstNonNull(job.getH1bTransferFit(), signal.h1bTransferFit()));
        job.setCapExemptFit(firstNonNull(job.getCapExemptFit(), signal.capExemptFit()));
    }

    private void applyVisaSignals(CandidateJobDetailResponse detail, String descriptionText) {
        VisaSignal signal = inferVisaSignal(detail, descriptionText);
        detail.setSponsorshipLanguage(firstNonBlank(detail.getSponsorshipLanguage(), signal.sponsorshipLanguage()));
        detail.setVisaConfidenceScore(firstNonNull(detail.getVisaConfidenceScore(), signal.visaConfidenceScore()));
        detail.setVisaReasons(firstNonNull(detail.getVisaReasons(), signal.reasons()));
        detail.setRequiresUsWorkAuthorization(firstNonNull(detail.getRequiresUsWorkAuthorization(), signal.requiresUsWorkAuthorization()));
        detail.setContractOrStaffingRisk(firstNonNull(detail.getContractOrStaffingRisk(), signal.contractOrStaffingRisk()));
        detail.setStemOptRisk(firstNonNull(detail.getStemOptRisk(), signal.stemOptRisk()));
        detail.setH1bTransferFit(firstNonNull(detail.getH1bTransferFit(), signal.h1bTransferFit()));
        detail.setCapExemptFit(firstNonNull(detail.getCapExemptFit(), signal.capExemptFit()));
    }

    private VisaSignal inferVisaSignal(CandidateJobSummaryResponse job, String descriptionText) {
        String text = normalizeSearchText(
                job.getTitle(),
                job.getCompanyName(),
                job.getDepartment(),
                job.getLocation(),
                job.getEmploymentType(),
                job.getTags() == null ? null : String.join(" ", job.getTags()),
                descriptionText
        );

        boolean noSponsorship = containsAny(text,
                "no sponsorship",
                "not sponsor",
                "will not sponsor",
                "does not sponsor",
                "unable to sponsor",
                "without sponsorship",
                "now or in the future");
        boolean sponsors = !noSponsorship && containsAny(text,
                "visa sponsorship",
                "sponsorship available",
                "will sponsor",
                "h-1b sponsorship",
                "h1b sponsorship",
                "employment visa",
                "immigration sponsorship");
        boolean requiresAuthorization = containsAny(text,
                "authorized to work",
                "work authorization",
                "eligible to work",
                "right to work",
                "employment authorization");
        boolean contractRisk = containsAny(text,
                "contract",
                "corp-to-corp",
                "c2c",
                "1099",
                "staffing",
                "vendor",
                "employer of record");
        boolean capExemptFit = containsAny(text,
                "university",
                "college",
                "nonprofit research",
                "research organization",
                "teaching hospital",
                "academic medical");

        String sponsorshipLanguage;
        int confidence;
        List<String> reasons = new ArrayList<>();
        if (sponsors) {
            sponsorshipLanguage = "SPONSORS";
            confidence = 88;
            reasons.add("Posting mentions sponsorship");
        } else if (noSponsorship) {
            sponsorshipLanguage = "NO_SPONSORSHIP";
            confidence = 8;
            reasons.add("Posting says sponsorship is not available");
        } else if (requiresAuthorization) {
            sponsorshipLanguage = "AUTHORIZATION_REQUIRED";
            confidence = 45;
            reasons.add("Posting requires work authorization");
        } else {
            sponsorshipLanguage = "UNKNOWN";
            confidence = 55;
            reasons.add("Sponsorship not stated");
        }

        if (contractRisk) {
            confidence = Math.max(0, confidence - 15);
            reasons.add("Contract/staffing language needs review");
        }
        if (capExemptFit) {
            confidence = Math.min(98, confidence + 8);
            reasons.add("Possible cap-exempt employer signal");
        }

        return new VisaSignal(
                sponsorshipLanguage,
                Math.max(0, Math.min(98, confidence)),
                reasons.stream().distinct().limit(4).toList(),
                requiresAuthorization || noSponsorship,
                contractRisk,
                noSponsorship || contractRisk,
                sponsors && !contractRisk,
                capExemptFit);
    }

    private VisaSignal inferVisaSignal(CandidateJobDetailResponse detail, String descriptionText) {
        String text = normalizeSearchText(
                detail.getTitle(),
                detail.getCompanyName(),
                detail.getDepartment(),
                detail.getLocation(),
                detail.getEmploymentType(),
                detail.getTags() == null ? null : String.join(" ", detail.getTags()),
                descriptionText
        );
        return inferVisaSignalFromText(text);
    }

    private VisaSignal inferVisaSignalFromText(String text) {
        boolean noSponsorship = containsAny(text,
                "no sponsorship",
                "not sponsor",
                "will not sponsor",
                "does not sponsor",
                "unable to sponsor",
                "without sponsorship",
                "now or in the future");
        boolean sponsors = !noSponsorship && containsAny(text,
                "visa sponsorship",
                "sponsorship available",
                "will sponsor",
                "h-1b sponsorship",
                "h1b sponsorship",
                "employment visa",
                "immigration sponsorship");
        boolean requiresAuthorization = containsAny(text,
                "authorized to work",
                "work authorization",
                "eligible to work",
                "right to work",
                "employment authorization");
        boolean contractRisk = containsAny(text,
                "contract",
                "corp-to-corp",
                "c2c",
                "1099",
                "staffing",
                "vendor",
                "employer of record");
        boolean capExemptFit = containsAny(text,
                "university",
                "college",
                "nonprofit research",
                "research organization",
                "teaching hospital",
                "academic medical");

        String sponsorshipLanguage;
        int confidence;
        List<String> reasons = new ArrayList<>();
        if (sponsors) {
            sponsorshipLanguage = "SPONSORS";
            confidence = 88;
            reasons.add("Posting mentions sponsorship");
        } else if (noSponsorship) {
            sponsorshipLanguage = "NO_SPONSORSHIP";
            confidence = 8;
            reasons.add("Posting says sponsorship is not available");
        } else if (requiresAuthorization) {
            sponsorshipLanguage = "AUTHORIZATION_REQUIRED";
            confidence = 45;
            reasons.add("Posting requires work authorization");
        } else {
            sponsorshipLanguage = "UNKNOWN";
            confidence = 55;
            reasons.add("Sponsorship not stated");
        }

        if (contractRisk) {
            confidence = Math.max(0, confidence - 15);
            reasons.add("Contract/staffing language needs review");
        }
        if (capExemptFit) {
            confidence = Math.min(98, confidence + 8);
            reasons.add("Possible cap-exempt employer signal");
        }

        return new VisaSignal(
                sponsorshipLanguage,
                Math.max(0, Math.min(98, confidence)),
                reasons.stream().distinct().limit(4).toList(),
                requiresAuthorization || noSponsorship,
                contractRisk,
                noSponsorship || contractRisk,
                sponsors && !contractRisk,
                capExemptFit);
    }

    private boolean isFresh(CandidateJobSummaryResponse job, int maxAgeDays) {
        if (maxAgeDays <= 0) {
            return true;
        }
        if (job.getSourceUpdatedAt() == null) {
            return false;
        }

        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(maxAgeDays);
        return !job.getSourceUpdatedAt().isBefore(cutoff);
    }

    private boolean isSupportedCountryJob(CandidateJobSummaryResponse job) {
        if (!"US".equalsIgnoreCase(supportedCountry) && !"USA".equalsIgnoreCase(supportedCountry)) {
            return true;
        }
        return hasUnitedStatesSignal(job.getLocation());
    }

    /** Apply user-selected filters before ranking and pagination. */
    private List<CandidateJobSummaryResponse> applyExplicitFilters(
            List<CandidateJobSummaryResponse> jobs,
            String workMode,
            Boolean salaryPosted,
            String experienceLevel,
            Boolean visaFriendly) {
        if (!hasExplicitFilters(workMode, salaryPosted, experienceLevel, visaFriendly)) {
            return jobs;
        }

        return jobs.stream()
                .filter(job -> matchesWorkModeFilter(job, workMode))
                .filter(job -> matchesSalaryFilter(job, salaryPosted))
                .filter(job -> matchesExperienceFilter(job, experienceLevel))
                .filter(job -> matchesVisaFilter(job, visaFriendly))
                .toList();
    }

    private boolean hasExplicitFilters(
            String workMode,
            Boolean salaryPosted,
            String experienceLevel,
            Boolean visaFriendly) {
        return (workMode != null && !workMode.isBlank() && !"all".equalsIgnoreCase(workMode))
                || Boolean.TRUE.equals(salaryPosted)
                || (experienceLevel != null && !experienceLevel.isBlank() && !"all".equalsIgnoreCase(experienceLevel))
                || Boolean.TRUE.equals(visaFriendly);
    }

    private boolean matchesWorkModeFilter(CandidateJobSummaryResponse job, String workMode) {
        if (workMode == null || workMode.isBlank() || "all".equalsIgnoreCase(workMode)) {
            return true;
        }

        String jobWorkMode = job.getWorkMode();
        if (jobWorkMode == null || jobWorkMode.isBlank()) {
            // If job has no work mode data, check if location text contains "remote"
            if ("remote".equalsIgnoreCase(workMode)) {
                String location = job.getLocation();
                return location != null && location.toLowerCase(Locale.US).contains("remote");
            }
            return true; // don't discard jobs missing work mode for hybrid/onsite filter
        }

        return jobWorkMode.equalsIgnoreCase(workMode);
    }

    private boolean matchesSalaryFilter(CandidateJobSummaryResponse job, Boolean salaryPosted) {
        if (!Boolean.TRUE.equals(salaryPosted)) {
            return true;
        }
        return hasListedSalary(job);
    }

    private boolean matchesExperienceFilter(CandidateJobSummaryResponse job, String experienceLevel) {
        if (experienceLevel == null || experienceLevel.isBlank() || "all".equalsIgnoreCase(experienceLevel)) {
            return true;
        }

        Integer years = job.getExperienceYears();
        if (years != null) {
            return switch (experienceLevel.toLowerCase(Locale.US)) {
                case "entry" -> years <= 2;
                case "mid" -> years >= 2 && years <= 5;
                case "senior" -> years >= 5 && years <= 8;
                case "staff" -> years >= 8;
                default -> true;
            };
        }

        String seniority = normalizeTerm(job.getSeniorityLabel());
        if (seniority == null || seniority.isBlank()) {
            return true;
        }

        return switch (experienceLevel.toLowerCase(Locale.US)) {
            case "entry" -> Set.of("intern", "entry").contains(seniority);
            case "mid" -> "mid".equals(seniority);
            case "senior" -> "senior".equals(seniority);
            case "staff" -> Set.of("staff+", "lead", "director+").contains(seniority);
            default -> true;
        };
    }

    private boolean matchesVisaFilter(CandidateJobSummaryResponse job, Boolean visaFriendly) {
        if (!Boolean.TRUE.equals(visaFriendly)) {
            return true;
        }

        String sponsorship = normalizeTerm(job.getSponsorshipLanguage());
        return sponsorship == null
                || sponsorship.isBlank()
                || "unknown".equals(sponsorship)
                || "sponsors".equals(sponsorship);
    }

    private boolean matchesCompany(CandidateJobSummaryResponse job, String company) {
        if (company == null || company.isBlank()) {
            return true;
        }

        return containsNormalized(job.getCompanyName(), company);
    }

    private boolean matchesQuery(CandidateJobSummaryResponse job, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }

        String[] tokens = query.trim().toLowerCase(Locale.US).split("\\s+");
        String tags = job.getTags() == null ? "" : String.join(" ", job.getTags());
        String combined = combineSearchableFields(
                job.getTitle(), job.getCompanyName(), job.getLocation(),
                job.getDepartment(), job.getSourceName(), tags);

        for (String token : tokens) {
            if (!combined.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private String combineSearchableFields(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (field != null) {
                sb.append(field.toLowerCase(Locale.US)).append(' ');
            }
        }
        return sb.toString();
    }

    private boolean containsNormalized(String value, String query) {
        if (value == null || query == null) {
            return false;
        }

        return value.toLowerCase(Locale.US).contains(query.trim().toLowerCase(Locale.US));
    }

    private boolean hasUnitedStatesSignal(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }

        String value = location.toUpperCase(Locale.US);
        return value.contains("UNITED STATES")
                || value.contains("USA")
                || value.contains("U.S.")
                || US_COUNTRY_PATTERN.matcher(location).find()
                || US_STATE_PATTERN.matcher(value).find()
                || US_STATE_NAME_PATTERN.matcher(location).find();
    }

    private List<CandidateJobSummaryResponse> dedupeAndSort(List<CandidateJobSummaryResponse> jobs) {
        Map<String, CandidateJobSummaryResponse> byKey = new LinkedHashMap<>();
        jobs.stream()
                .sorted(jobComparator())
                .forEach(job -> byKey.putIfAbsent(dedupeKey(job), job));
        return new ArrayList<>(byKey.values());
    }

    private CandidateJobPageResponse toJobPage(List<CandidateJobSummaryResponse> jobs, int limit, int offset) {
        boolean hasMore = jobs.size() > limit;
        List<CandidateJobSummaryResponse> pageJobs = hasMore ? jobs.subList(0, limit) : jobs;
        return CandidateJobPageResponse.builder()
                .jobs(pageJobs)
                .limit(limit)
                .offset(offset)
                .hasMore(hasMore)
                .nextOffset(hasMore ? offset + pageJobs.size() : null)
                .build();
    }

    private CandidateJobPageResponse toRankedJobPage(List<CandidateJobSummaryResponse> jobs, int limit, int offset) {
        List<CandidateJobSummaryResponse> rankedJobs = jobs == null ? List.of() : jobs;
        int fromIndex = Math.min(offset, rankedJobs.size());
        int toIndex = Math.min(fromIndex + limit, rankedJobs.size());
        List<CandidateJobSummaryResponse> pageJobs = rankedJobs.subList(fromIndex, toIndex);
        boolean hasMore = rankedJobs.size() > toIndex;

        return CandidateJobPageResponse.builder()
                .jobs(pageJobs)
                .limit(limit)
                .offset(offset)
                .hasMore(hasMore)
                .nextOffset(hasMore ? offset + pageJobs.size() : null)
                .build();
    }

    private Flux<CandidateJobSummaryResponse> personalizeJobs(String candidateEmail, List<CandidateJobSummaryResponse> jobs) {
        if (jobs == null || jobs.isEmpty() || candidateEmail == null || candidateEmail.isBlank()) {
            return Flux.fromIterable(jobs == null ? List.of() : jobs);
        }

        return resolveCandidateMatchContext(candidateEmail)
                .map(context -> rankPersonalizedJobs(jobs, context))
                .defaultIfEmpty(jobs)
                .flatMapMany(Flux::fromIterable);
    }

    private Mono<CandidateJobPageResponse> personalizePage(String candidateEmail, CandidateJobPageResponse page) {
        if (page == null || page.getJobs() == null || page.getJobs().isEmpty() || candidateEmail == null || candidateEmail.isBlank()) {
            return Mono.just(page);
        }

        return resolveCandidateMatchContext(candidateEmail)
                .map(context -> {
                    List<CandidateJobSummaryResponse> personalizedJobs = rankPersonalizedJobs(page.getJobs(), context);
                    page.setJobs(personalizedJobs);
                    return page;
                })
                .defaultIfEmpty(page);
    }

    private Mono<CandidateJobDetailResponse> personalizeDetail(String candidateEmail, CandidateJobDetailResponse detail) {
        if (detail == null || candidateEmail == null || candidateEmail.isBlank()) {
            return Mono.just(detail);
        }

        return resolveCandidateMatchContext(candidateEmail)
                .map(context -> applyCandidateMatch(detail, context))
                .defaultIfEmpty(detail);
    }

    private List<CandidateJobSummaryResponse> rankPersonalizedJobs(List<CandidateJobSummaryResponse> jobs, CandidateMatchContext context) {
        return (jobs == null ? List.<CandidateJobSummaryResponse>of() : jobs).stream()
                .filter(job -> passesTargetRoleFilter(job, context))
                .filter(job -> passesLocationFilter(job, context))
                .filter(job -> passesSeniorityFilter(job, context))
                .map(job -> applyCandidateMatch(job, context))
                .sorted(personalizedJobComparator())
                .toList();
    }

    /**
     * Hard filter: when the user has a target role or a resume-derived role, do not show
     * unrelated functional tracks. A weak "58% match" is not useful for a SWE seeing
     * compliance, sales, or account roles.
     */
    private boolean passesTargetRoleFilter(CandidateJobSummaryResponse job, CandidateMatchContext context) {
        if (job == null || context == null || context.targetRoles() == null || context.targetRoles().isEmpty()) {
            return true;
        }

        RoleMatchClassifier.RoleIntent profileIntent = candidateRoleIntent(context);
        RoleMatchClassifier.RoleIntent jobIntent = jobRoleIntent(job);
        if (!ROLE_MATCH_CLASSIFIER.careerTrackCompatible(profileIntent, jobIntent)) {
            return false;
        }

        MatchComponent roleFit = scoreRoleFit(job, context, jobMatchText(job));
        if (roleFit.score() >= 8) {
            return true;
        }

        return ROLE_MATCH_CLASSIFIER.compatible(profileIntent, jobIntent);
    }

    /**
     * Hard filter: remove jobs that don't match the candidate's location unless the job is remote
     * or the candidate is open to relocation.
     */
    private boolean passesLocationFilter(CandidateJobSummaryResponse job, CandidateMatchContext context) {
        if (context.location() == null || context.location().isBlank()) {
            return true;
        }
        if (Boolean.TRUE.equals(context.openToRelocation())) {
            return true;
        }

        String jobWorkMode = normalizeTerm(job.getWorkMode());
        if ("remote".equals(jobWorkMode)) {
            return true;
        }

        String jobLocation = job.getLocation();
        if (jobLocation == null || jobLocation.isBlank()) {
            return true; // don't discard jobs with no location data
        }

        String normalizedJobLocation = jobLocation.toLowerCase(Locale.US);

        // Direct location match (city or state substring)
        if (normalizedJobLocation.contains(context.location())) {
            return true;
        }

        // Metro area match
        if (matchesMetroArea(normalizedJobLocation, context.location())) {
            return true;
        }

        // Same state match — extract state abbreviation from both
        if (sameState(normalizedJobLocation, context.location())) {
            return true;
        }

        // Job mentions remote in location text
        if (normalizedJobLocation.contains("remote")) {
            return true;
        }

        return false;
    }

    private boolean sameState(String jobLocation, String candidateLocation) {
        String jobState = extractStateAbbreviation(jobLocation);
        String candidateState = extractStateAbbreviation(candidateLocation);
        return jobState != null && jobState.equals(candidateState);
    }

    private String extractStateAbbreviation(String location) {
        var matcher = US_STATE_PATTERN.matcher(location.toUpperCase(Locale.US));
        return matcher.find() ? matcher.group(2) : null;
    }

    /**
     * Hard filter: remove jobs whose seniority level is clearly mismatched for the candidate's
     * years of experience. With ~4 years, filter out director/VP/principal and intern/new-grad.
     */
    private boolean passesSeniorityFilter(CandidateJobSummaryResponse job, CandidateMatchContext context) {
        Integer years = context.yearsOfExperience();
        if (years == null || years < 0) {
            return true; // no experience data, can't filter
        }

        String title = job.getTitle();
        if (title == null || title.isBlank()) {
            return true;
        }

        String normalizedTitle = normalizedTermText(title);

        // Filter out intern/new-grad roles for candidates with 2+ years
        if (years >= 2 && isInternOrNewGrad(normalizedTitle)) {
            return false;
        }

        // Filter out people-management tracks unless the candidate is already senior enough
        if (years < 8 && isPeopleManagerTitle(normalizedTitle)) {
            return false;
        }

        // Filter out director/VP/C-level for candidates with < 10 years
        if (years < 10 && isDirectorOrAbove(normalizedTitle)) {
            return false;
        }

        // Filter out principal/staff for candidates with < 7 years
        if (years < 7 && isPrincipalOrStaff(normalizedTitle)) {
            return false;
        }

        // Filter out entry-level/junior for candidates with 5+ years
        if (years >= 5 && isEntryLevel(normalizedTitle)) {
            return false;
        }

        return true;
    }

    private boolean isInternOrNewGrad(String title) {
        return containsTerm(title, "intern") || containsTerm(title, "internship")
                || containsTerm(title, "new grad") || containsTerm(title, "new graduate");
    }

    private boolean isDirectorOrAbove(String title) {
        return containsTerm(title, "director") || containsTerm(title, "vp")
                || containsTerm(title, "vice president") || containsTerm(title, "chief")
                || containsTerm(title, "cto") || containsTerm(title, "cfo")
                || containsTerm(title, "ceo");
    }

    private boolean isPeopleManagerTitle(String title) {
        if (containsTerm(title, "product manager")
                || containsTerm(title, "project manager")
                || containsTerm(title, "program manager")
                || containsTerm(title, "account manager")
                || containsTerm(title, "customer success manager")
                || containsTerm(title, "marketing manager")
                || containsTerm(title, "social media manager")) {
            return false;
        }
        return containsTerm(title, "engineering manager")
                || containsTerm(title, "senior manager")
                || containsTerm(title, "people manager")
                || containsTerm(title, "manager");
    }

    private boolean isPrincipalOrStaff(String title) {
        return containsTerm(title, "principal") || containsTerm(title, "staff")
                || containsTerm(title, "distinguished");
    }

    private boolean isEntryLevel(String title) {
        return containsTerm(title, "junior") || containsTerm(title, "jr")
                || containsTerm(title, "entry level") || containsTerm(title, "entry-level")
                || title.endsWith(" i") || title.contains(" i ");
    }

    private boolean hasCandidateEmail(String candidateEmail) {
        return candidateEmail != null && !candidateEmail.isBlank();
    }

    private Mono<CandidateMatchContext> resolveCandidateMatchContext(String candidateEmail) {
        if (candidateEmail == null || candidateEmail.isBlank()) {
            return Mono.empty();
        }

        return userRepository.findByEmail(candidateEmail)
                .flatMap(user -> candidateProfileRepository.findByUserId(user.getId()))
                .map(this::toCandidateMatchContext)
                .filter(context -> !context.isEmpty())
                .onErrorResume(error -> {
                    log.warn("Unable to personalize jobs for candidate {}: {}", candidateEmail, error.getMessage());
                    return Mono.empty();
                });
    }

    private CandidateMatchContext toCandidateMatchContext(CandidateProfile profile) {
        Map<String, Object> matchPreferences = mapFromJson(profile.getMatchPreferences() == null ? null : profile.getMatchPreferences().asString());
        Set<String> targetRoles = normalizedTerms(stringsFromObject(matchPreferences.get("targetRoles")));
        if (targetRoles.isEmpty()) {
            targetRoles = normalizedTerms(inferredTargetRoles(profile));
        }

        return new CandidateMatchContext(
                normalizedTerms(stringsFromJson(profile.getSkills() == null ? null : profile.getSkills().asString())),
                targetRoles,
                normalizedTerms(stringsFromObject(matchPreferences.get("mustHaveSkills"))),
                normalizedTerms(stringsFromObject(matchPreferences.get("niceToHaveSkills"))),
                normalizedTerms(stringsFromObject(matchPreferences.get("avoidKeywords"))),
                normalizeTerm(profile.getHeadline()),
                normalizeTerm(profile.getLocation()),
                normalizeTerm(profile.getPreferredWorkMode()),
                normalizeTerm(profile.getPreferredEmploymentType()),
                booleanFromObject(matchPreferences.get("needsSponsorship")),
                booleanFromObject(matchPreferences.get("needsSponsorshipNow")),
                booleanFromObject(matchPreferences.get("needsSponsorshipLater")),
                booleanFromObject(matchPreferences.get("requiresEVerify")),
                booleanFromObject(matchPreferences.get("openToCapExemptEmployers")),
                booleanFromObject(matchPreferences.get("openToRelocation")),
                booleanFromObject(matchPreferences.get("salaryRequired")),
                booleanFromObject(matchPreferences.get("easyApplyOnly")),
                booleanFromObject(matchPreferences.get("directCompanySourceOnly")),
                booleanFromObject(matchPreferences.get("noTakeHome")),
                booleanFromObject(matchPreferences.get("stabilityFirst")),
                profile.getSalaryExpectationMin(),
                profile.getSalaryExpectationMax(),
                computeYearsOfExperience(profile.getExperience()));
    }

    @SuppressWarnings("unchecked")
    private List<String> inferredTargetRoles(CandidateProfile profile) {
        List<String> roles = new ArrayList<>();
        String headlineRole = roleFromHeadline(profile.getHeadline());
        if (headlineRole != null) {
            roles.add(headlineRole);
        }

        if (profile.getExperience() != null && profile.getExperience().asString() != null) {
            try {
                List<Map<String, Object>> entries = objectMapper.readValue(profile.getExperience().asString(), List.class);
                for (Map<String, Object> entry : entries == null ? List.<Map<String, Object>>of() : entries) {
                    Object title = entry.get("title");
                    if (title instanceof String titleText && !titleText.isBlank()) {
                        roles.add(roleFromHeadline(titleText));
                    }
                    if (roles.size() >= 3) {
                        break;
                    }
                }
            } catch (JsonProcessingException ignored) {
                // Resume-derived roles are a ranking hint only.
            }
        }

        return roles.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String roleFromHeadline(String headline) {
        if (headline == null || headline.isBlank()) {
            return null;
        }

        String value = headline.strip();
        value = value.replaceAll("(?i)\\s+at\\s+.+$", "");
        value = value.replaceAll("\\s*[|@]\\s*.+$", "");
        value = value.replaceAll("\\s{2,}", " ").strip();
        if (value.length() < 3 || value.length() > 80) {
            return null;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Integer computeYearsOfExperience(io.r2dbc.postgresql.codec.Json experienceJson) {
        if (experienceJson == null) {
            return null;
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(experienceJson.asString(), List.class);
            if (entries == null || entries.isEmpty()) {
                return null;
            }

            int totalMonths = 0;
            for (Map<String, Object> entry : entries) {
                String startDate = entry.get("startDate") instanceof String s ? s : null;
                String endDate = entry.get("endDate") instanceof String s ? s : null;
                Boolean current = entry.get("current") instanceof Boolean b ? b : null;

                if (startDate == null || startDate.isBlank()) {
                    continue;
                }

                java.time.YearMonth start = parseYearMonth(startDate);
                if (start == null) {
                    continue;
                }

                java.time.YearMonth end;
                if (Boolean.TRUE.equals(current) || endDate == null || endDate.isBlank()) {
                    end = java.time.YearMonth.now();
                } else {
                    end = parseYearMonth(endDate);
                    if (end == null) {
                        end = java.time.YearMonth.now();
                    }
                }

                long months = java.time.temporal.ChronoUnit.MONTHS.between(start, end);
                if (months > 0) {
                    totalMonths += (int) months;
                }
            }

            return totalMonths > 0 ? totalMonths / 12 : null;
        } catch (Exception e) {
            log.debug("Unable to compute years of experience: {}", e.getMessage());
            return null;
        }
    }

    private java.time.YearMonth parseYearMonth(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }

        String value = date.strip()
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replaceAll("\\s+", " ");
        try {
            if (value.length() == 7) { // "2022-01"
                return java.time.YearMonth.parse(value);
            } else if (value.length() >= 10 && Character.isDigit(value.charAt(0))) { // "2022-01-15"
                return java.time.YearMonth.from(java.time.LocalDate.parse(value.substring(0, 10)));
            } else if (value.length() == 4 && value.chars().allMatch(Character::isDigit)) { // "2022"
                return java.time.YearMonth.of(Integer.parseInt(value), 1);
            }
        } catch (Exception ignored) {
        }

        for (DateTimeFormatter formatter : List.of(MONTH_YEAR_FORMATTER, SHORT_MONTH_YEAR_FORMATTER)) {
            try {
                return java.time.YearMonth.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private CandidateJobSummaryResponse applyCandidateMatch(CandidateJobSummaryResponse job, CandidateMatchContext context) {
        if (job == null) {
            return null;
        }
        return applyCandidateMatch(job, context, jobMatchText(job));
    }

    private CandidateJobSummaryResponse applyCandidateMatch(CandidateJobSummaryResponse job, CandidateMatchContext context, String haystack) {
        if (job == null || context == null || context.isEmpty()) {
            return job;
        }

        List<String> reasons = new ArrayList<>();

        MatchComponent roleFit = scoreRoleFit(job, context, haystack);
        MatchComponent skillFit = scoreSkillFit(context, haystack);
        MatchComponent preferenceFit = scorePreferenceFit(job, context, haystack);
        MatchComponent qualityFit = scoreQualityFit(job);
        MatchComponent visaFit = scoreVisaFit(job, context);
        MatchComponent avoidPenalty = scoreAvoidPenalty(context, haystack);

        int score = 42
                + roleFit.score()
                + skillFit.score()
                + preferenceFit.score()
                + qualityFit.score()
                + visaFit.score()
                - avoidPenalty.score();

        reasons.addAll(roleFit.reasons());
        reasons.addAll(skillFit.reasons());
        reasons.addAll(preferenceFit.reasons());
        reasons.addAll(qualityFit.reasons());
        reasons.addAll(visaFit.reasons());
        reasons.addAll(avoidPenalty.reasons());

        if (!context.targetRoles().isEmpty() && roleFit.score() < 8) {
            score = Math.min(score, 78);
            reasons.add("Role is outside your target titles");
        }
        if (avoidPenalty.score() >= 12) {
            score = Math.min(score, 72);
        }

        job.setMatchScore(Math.max(25, Math.min(98, score)));
        job.setMatchReasons(reasons.stream().distinct().limit(6).toList());
        return job;
    }

    private CandidateJobDetailResponse applyCandidateMatch(CandidateJobDetailResponse detail, CandidateMatchContext context) {
        if (detail == null || context == null || context.isEmpty()) {
            return detail;
        }

        CandidateJobSummaryResponse summary = CandidateJobSummaryResponse.builder()
                .jobId(detail.getJobId())
                .sourceType(detail.getSourceType())
                .sourceName(detail.getSourceName())
                .sourceBoardToken(detail.getSourceBoardToken())
                .externalJobId(detail.getExternalJobId())
                .title(detail.getTitle())
                .companyName(detail.getCompanyName())
                .companyDomain(detail.getCompanyDomain())
                .companyLogoUrl(detail.getCompanyLogoUrl())
                .department(detail.getDepartment())
                .location(detail.getLocation())
                .workMode(detail.getWorkMode())
                .employmentType(detail.getEmploymentType())
                .salaryLabel(detail.getSalaryLabel())
                .applyUrl(detail.getApplyUrl())
                .jobUrl(detail.getJobUrl())
                .applyMode(detail.getApplyMode())
                .easyApplyAvailable(false)
                .sourceUpdatedAt(detail.getSourceUpdatedAt())
                .postedLabel(detail.getPostedLabel())
                .matchScore(detail.getMatchScore())
                .matchReasons(detail.getMatchReasons())
                .connectionsCount(detail.getConnectionsCount())
                .tags(detail.getTags())
                .jobQualityScore(detail.getJobQualityScore())
                .qualityReasons(detail.getQualityReasons())
                .totalCompLabel(detail.getTotalCompLabel())
                .compensationConfidence(detail.getCompensationConfidence())
                .sponsorshipLanguage(detail.getSponsorshipLanguage())
                .visaConfidenceScore(detail.getVisaConfidenceScore())
                .visaReasons(detail.getVisaReasons())
                .requiresUsWorkAuthorization(detail.getRequiresUsWorkAuthorization())
                .contractOrStaffingRisk(detail.getContractOrStaffingRisk())
                .stemOptRisk(detail.getStemOptRisk())
                .h1bTransferFit(detail.getH1bTransferFit())
                .capExemptFit(detail.getCapExemptFit())
                .build();

        applyCandidateMatch(summary, context, jobMatchText(detail));
        detail.setMatchScore(summary.getMatchScore());
        detail.setMatchReasons(summary.getMatchReasons());
        return detail;
    }

    private MatchComponent scoreRoleFit(CandidateJobSummaryResponse job, CandidateMatchContext context, String haystack) {
        int bestScore = 0;
        String bestRole = null;
        String titleText = normalizedTermText(job.getTitle());
        String roleText = jobRoleText(job);

        for (String role : context.targetRoles()) {
            String normalizedRole = normalizedTermText(role);
            if (normalizedRole.isBlank()) {
                continue;
            }

            List<String> tokens = meaningfulRoleTokens(normalizedRole);
            int score = 0;
            if (containsTerm(titleText, normalizedRole)) {
                score = 24;
            } else if (!tokens.isEmpty() && tokens.stream().allMatch(token -> containsTerm(titleText, token))) {
                score = 21;
            } else if (containsTerm(roleText, normalizedRole)) {
                score = 18;
            } else if (!tokens.isEmpty()) {
                long titleHits = tokens.stream().filter(token -> containsTerm(titleText, token)).count();
                long anyHits = tokens.stream().filter(token -> containsTerm(roleText, token)).count();
                score = Math.max((int) Math.min(14, titleHits * 7), (int) Math.min(10, anyHits * 5));
            }

            if (score > bestScore) {
                bestScore = score;
                bestRole = role;
            }
        }

        if (bestScore == 0 && context.headline() != null && containsTerm(roleText, context.headline())) {
            bestScore = 6;
            bestRole = context.headline();
        }

        RoleMatchClassifier.RoleIntent profileIntent = candidateRoleIntent(context);
        RoleMatchClassifier.RoleIntent jobIntent = jobRoleIntent(job);
        if (bestScore == 0
                && ROLE_MATCH_CLASSIFIER.compatible(profileIntent, jobIntent)
                && ROLE_MATCH_CLASSIFIER.careerTrackCompatible(profileIntent, jobIntent)
                && jobIntent.isKnown()) {
            bestScore = 14;
            bestRole = ROLE_MATCH_CLASSIFIER.display(jobIntent);
        }

        if (bestScore == 0) {
            return MatchComponent.empty();
        }

        return new MatchComponent(bestScore, List.of("Role fit: " + displayTerm(bestRole)));
    }

    private RoleMatchClassifier.RoleIntent candidateRoleIntent(CandidateMatchContext context) {
        if (context == null) {
            return new RoleMatchClassifier.RoleIntent(Set.of());
        }
        return ROLE_MATCH_CLASSIFIER.classifyProfile(
                context.targetRoles(),
                context.headline(),
                context.skills());
    }

    private RoleMatchClassifier.RoleIntent jobRoleIntent(CandidateJobSummaryResponse job) {
        if (job == null) {
            return new RoleMatchClassifier.RoleIntent(Set.of());
        }
        return ROLE_MATCH_CLASSIFIER.classifyJob(
                job.getTitle(),
                job.getDepartment(),
                job.getTags());
    }

    private List<String> retrievalQueriesFor(CandidateMatchContext context, String query) {
        Set<String> queries = new LinkedHashSet<>();

        String normalizedQuery = normalizedTermText(query);
        if (!normalizedQuery.isBlank()) {
            queries.add(normalizedQuery);

            String roleFromQuery = retrievalQueryFromRole(normalizedQuery);
            if (roleFromQuery != null) {
                queries.add(roleFromQuery);
            }
        }

        if (context == null) {
            return queries.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .limit(8)
                    .toList();
        }

        for (String role : context.targetRoles() == null ? Set.<String>of() : context.targetRoles()) {
            String roleQuery = retrievalQueryFromRole(role);
            if (roleQuery != null) {
                queries.add(roleQuery);
            }
        }

        RoleMatchClassifier.RoleIntent intent = candidateRoleIntent(context);
        if (intent.families().contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING)) {
            queries.add("software engineer");
        }
        if (intent.families().contains(RoleMatchClassifier.RoleFamily.DATA_ENGINEERING)) {
            queries.add("data engineer");
        }
        if (intent.families().contains(RoleMatchClassifier.RoleFamily.DATA_SCIENCE)) {
            queries.add("machine learning engineer");
        }
        if (intent.families().contains(RoleMatchClassifier.RoleFamily.SECURITY_ENGINEERING)) {
            queries.add("security engineer");
        }

        return queries.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
            .limit(8)
                .toList();
    }

    private String retrievalQueryFromRole(String role) {
        String normalized = normalizedTermText(role);
        if (normalized.isBlank()) {
            return null;
        }

        normalized = normalized.replaceAll("(^|\\s)(i|ii|iii|iv|v|senior|sr|junior|jr|staff|principal|lead)(\\s|$)", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (containsTerm(normalized, "full stack")) {
            return "full stack engineer";
        }
        if (containsTerm(normalized, "front end") || containsTerm(normalized, "frontend")) {
            return "frontend engineer";
        }
        if (containsTerm(normalized, "back end") || containsTerm(normalized, "backend")) {
            return "backend engineer";
        }
        if (containsTerm(normalized, "site reliability") || containsTerm(normalized, "sre")) {
            return "site reliability engineer";
        }
        if (containsTerm(normalized, "software engineer") || containsTerm(normalized, "software developer")) {
            return "software engineer";
        }
        if (containsTerm(normalized, "data engineer")) {
            return "data engineer";
        }
        if (containsTerm(normalized, "machine learning") || containsTerm(normalized, "ml engineer")) {
            return "machine learning engineer";
        }
        if (containsTerm(normalized, "security engineer")) {
            return "security engineer";
        }

        List<String> tokens = meaningfulRoleTokens(normalized);
        if (tokens.isEmpty()) {
            return null;
        }
        return String.join(" ", tokens.stream().limit(3).toList());
    }

    private MatchComponent scoreSkillFit(CandidateMatchContext context, String haystack) {
        List<String> mustHaveMatches = matchedTerms(context.mustHaveSkills(), haystack);
        List<String> skillMatches = matchedTerms(context.skills(), haystack);
        List<String> niceToHaveMatches = matchedTerms(context.niceToHaveSkills(), haystack);

        int score = Math.min(15, mustHaveMatches.size() * 7)
                + Math.min(12, skillMatches.size() * 3)
                + Math.min(5, niceToHaveMatches.size() * 2);
        score = Math.min(24, score);

        List<String> reasons = new ArrayList<>();
        if (!mustHaveMatches.isEmpty()) {
            reasons.add("Must-have match: " + displayTerms(mustHaveMatches, 3));
        }
        if (!skillMatches.isEmpty()) {
            reasons.add("Skill match: " + displayTerms(skillMatches, 4));
        }
        if (mustHaveMatches.isEmpty() && skillMatches.isEmpty() && !niceToHaveMatches.isEmpty()) {
            reasons.add("Nice-to-have match: " + displayTerms(niceToHaveMatches, 3));
        }

        return new MatchComponent(score, reasons);
    }

    private MatchComponent scorePreferenceFit(CandidateJobSummaryResponse job, CandidateMatchContext context, String haystack) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        String workMode = normalizeTerm(job.getWorkMode());
        if (context.preferredWorkMode() != null && workMode != null) {
            if (workModeCompatible(context.preferredWorkMode(), workMode)) {
                score += 6;
                reasons.add("Work mode fits");
            } else if (!Boolean.TRUE.equals(context.openToRelocation())) {
                score -= 5;
                reasons.add("Work mode may not fit");
            }
        }

        if (context.location() != null) {
            if (containsTerm(haystack, context.location())) {
                score += 5;
                reasons.add("Location fit");
            } else if (matchesMetroArea(haystack, context.location())) {
                score += 4;
                reasons.add("Metro area match");
            } else if (containsTerm(haystack, "remote")) {
                score += 4;
                reasons.add("Remote-friendly");
            } else if (Boolean.TRUE.equals(context.openToRelocation())) {
                score += 2;
                reasons.add("Relocation keeps this open");
            } else {
                score -= 4;
            }
        }

        // Salary: compare actual range when available, not just presence
        if (hasListedSalary(job)) {
            int salaryFit = scoreSalaryRangeFit(job.getSalaryLabel(), context.salaryExpectationMin(), context.salaryExpectationMax());
            score += salaryFit;
            if (salaryFit >= 5) {
                reasons.add("Salary in range");
            } else if (salaryFit > 0) {
                reasons.add("Salary listed");
            } else if (salaryFit < -5) {
                reasons.add("Salary below expectations");
            }
        } else if (Boolean.TRUE.equals(context.salaryRequired()) || context.salaryExpectationMin() != null || context.salaryExpectationMax() != null) {
            score -= 14;
            reasons.add("Salary missing");
        }

        // Employment type matching
        if (context.preferredEmploymentType() != null && job.getEmploymentType() != null) {
            String jobEmpType = normalizeTerm(job.getEmploymentType());
            if (jobEmpType != null && employmentTypeCompatible(context.preferredEmploymentType(), jobEmpType)) {
                score += 4;
                reasons.add("Employment type matches");
            } else if (jobEmpType != null && !jobEmpType.isBlank()) {
                score -= 6;
                reasons.add("Employment type mismatch");
            }
        }

        if (Boolean.TRUE.equals(context.easyApplyOnly())) {
            if (Boolean.TRUE.equals(job.getEasyApplyAvailable())) {
                score += 3;
                reasons.add("Easy apply");
            } else {
                score -= 8;
                reasons.add("Not easy apply");
            }
        }

        if (Boolean.TRUE.equals(context.directCompanySourceOnly())) {
            if (isDirectSource(job)) {
                score += 4;
                reasons.add("Direct company source");
            } else {
                score -= 10;
                reasons.add("Not a direct source");
            }
        }

        return new MatchComponent(Math.max(-25, Math.min(18, score)), reasons);
    }

    private MatchComponent scoreQualityFit(CandidateJobSummaryResponse job) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (job.getJobQualityScore() != null) {
            score += Math.min(8, Math.max(0, job.getJobQualityScore() - 50) / 6);
            if (job.getJobQualityScore() >= 90) {
                reasons.add("High-quality posting");
            }
        }
        if (isRecent(job.getSourceUpdatedAt(), 3)) {
            score += 4;
            reasons.add("Fresh posting");
        } else if (isRecent(job.getSourceUpdatedAt(), 7)) {
            score += 3;
            reasons.add("Posted this week");
        } else if (isRecent(job.getSourceUpdatedAt(), 14)) {
            score += 2;
        } else if (isRecent(job.getSourceUpdatedAt(), 30)) {
            // No bonus, no penalty
        } else if (job.getSourceUpdatedAt() != null) {
            // Older than 30 days — likely stale/ghost job
            score -= 6;
            reasons.add("Older posting — may be filled");
        }
        if (firstNonBlank(job.getApplyUrl(), job.getJobUrl()) != null && isDirectSource(job)) {
            score += 3;
        }

        return new MatchComponent(Math.max(-6, Math.min(12, score)), reasons);
    }

    private MatchComponent scoreVisaFit(CandidateJobSummaryResponse job, CandidateMatchContext context) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        boolean needsSponsorship = Boolean.TRUE.equals(context.needsSponsorship())
                || Boolean.TRUE.equals(context.needsSponsorshipNow())
                || Boolean.TRUE.equals(context.needsSponsorshipLater());

        if (needsSponsorship) {
            String language = job.getSponsorshipLanguage() == null ? "UNKNOWN" : job.getSponsorshipLanguage().toUpperCase(Locale.US);
            switch (language) {
                case "SPONSORS" -> {
                    score += 12;
                    reasons.add("Sponsorship mentioned");
                }
                case "NO_SPONSORSHIP" -> {
                    score -= 35;
                    reasons.add("Sponsorship unlikely");
                }
                case "AUTHORIZATION_REQUIRED" -> {
                    score -= 16;
                    reasons.add("Authorization language needs review");
                }
                default -> {
                    score -= 8;
                    reasons.add("Sponsorship unclear");
                }
            }
            if (Boolean.TRUE.equals(job.getH1bTransferFit())) {
                score += 8;
                reasons.add("H-1B transfer signal");
            }
            if (Boolean.TRUE.equals(job.getContractOrStaffingRisk())) {
                score -= 12;
                reasons.add("Contract/staffing risk");
            }
        }

        if (Boolean.TRUE.equals(context.requiresEVerify()) && Boolean.TRUE.equals(job.getStemOptRisk())) {
            score -= 15;
            reasons.add("STEM OPT risk");
        }
        if (Boolean.TRUE.equals(context.openToCapExemptEmployers()) && Boolean.TRUE.equals(job.getCapExemptFit())) {
            score += 8;
            reasons.add("Possible cap-exempt fit");
        }

        return new MatchComponent(Math.max(-45, Math.min(18, score)), reasons);
    }

    private MatchComponent scoreAvoidPenalty(CandidateMatchContext context, String haystack) {
        List<String> avoidMatches = matchedTerms(context.avoidKeywords(), haystack);
        if (avoidMatches.isEmpty()) {
            return MatchComponent.empty();
        }

        int penalty = Math.min(25, avoidMatches.size() * 12);
        return new MatchComponent(penalty, List.of("Avoid keyword: " + displayTerms(avoidMatches, 2)));
    }

    private boolean containsTerm(String haystack, String term) {
        if (haystack == null || term == null || term.isBlank()) {
            return false;
        }

        String normalizedHaystack = " " + normalizedTermText(haystack) + " ";
        String normalizedTerm = normalizedTermText(term);
        if (normalizedTerm.isBlank()) {
            return false;
        }

        if (normalizedTerm.contains(" ")) {
            return normalizedHaystack.contains(" " + normalizedTerm + " ");
        }

        return normalizedHaystack.contains(" " + normalizedTerm + " ");
    }

    private String jobMatchText(CandidateJobSummaryResponse job) {
        return normalizedTermText(joinNonBlank(" ",
                job.getTitle(),
                job.getCompanyName(),
                job.getDepartment(),
                job.getLocation(),
                job.getWorkMode(),
                job.getEmploymentType(),
                job.getSalaryLabel(),
                job.getSourceName(),
                job.getTags() == null ? null : String.join(" ", job.getTags())));
    }

    private String jobRoleText(CandidateJobSummaryResponse job) {
        return normalizedTermText(joinNonBlank(" ",
                job.getTitle(),
                job.getDepartment(),
                job.getTags() == null ? null : String.join(" ", job.getTags())));
    }

    private String jobMatchText(CandidateJobDetailResponse job) {
        return normalizedTermText(joinNonBlank(" ",
                job.getTitle(),
                job.getCompanyName(),
                job.getDepartment(),
                job.getLocation(),
                job.getWorkMode(),
                job.getEmploymentType(),
                job.getSalaryLabel(),
                job.getSourceName(),
                job.getDescriptionText(),
                job.getDescriptionExcerpt(),
                job.getTags() == null ? null : String.join(" ", job.getTags())));
    }

    private List<String> matchedTerms(Set<String> terms, String haystack) {
        if (terms == null || terms.isEmpty() || haystack == null || haystack.isBlank()) {
            return List.of();
        }

        return terms.stream()
                .filter(term -> containsTerm(haystack, term))
                .toList();
    }

    private List<String> meaningfulRoleTokens(String role) {
        List<String> tokens = List.of(normalizedTermText(role).split(" ")).stream()
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() > 2)
                .filter(token -> !GENERIC_ROLE_WORDS.contains(token))
                .toList();

        if (!tokens.isEmpty()) {
            return tokens;
        }

        return List.of(normalizedTermText(role).split(" ")).stream()
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() > 2)
                .filter(token -> !"full".equals(token))
                .toList();
    }

    private boolean workModeCompatible(String preferredWorkMode, String jobWorkMode) {
        String preferred = normalizedTermText(preferredWorkMode);
        String actual = normalizedTermText(jobWorkMode);
        if (preferred.isBlank() || actual.isBlank()) {
            return false;
        }

        if (preferred.equals(actual)) {
            return true;
        }

        return "remote".equals(preferred) && "hybrid".equals(actual);
    }

    /**
     * Match metro area synonyms — user says "San Francisco" but job says "Bay Area" or "SF".
     */
    private boolean matchesMetroArea(String haystack, String userLocation) {
        String loc = normalizedTermText(userLocation);
        for (MetroSynonyms metro : METRO_SYNONYMS) {
            if (metro.matches(loc)) {
                return metro.terms().stream().anyMatch(term -> containsTerm(haystack, term));
            }
        }
        return false;
    }

    private static final List<MetroSynonyms> METRO_SYNONYMS = List.of(
            new MetroSynonyms(List.of("new york", "nyc", "manhattan", "brooklyn", "new york city")),
            new MetroSynonyms(List.of("san francisco", "sf", "bay area", "san jose", "silicon valley", "palo alto", "mountain view", "sunnyvale")),
            new MetroSynonyms(List.of("los angeles", "la", "santa monica", "burbank", "pasadena")),
            new MetroSynonyms(List.of("chicago", "chicagoland")),
            new MetroSynonyms(List.of("seattle", "bellevue", "redmond", "puget sound")),
            new MetroSynonyms(List.of("austin", "austin tx")),
            new MetroSynonyms(List.of("dallas", "fort worth", "dfw", "dallas-fort worth", "plano", "irving")),
            new MetroSynonyms(List.of("boston", "cambridge ma", "cambridge")),
            new MetroSynonyms(List.of("denver", "boulder", "colorado")),
            new MetroSynonyms(List.of("atlanta", "atl")),
            new MetroSynonyms(List.of("miami", "south florida", "fort lauderdale")),
            new MetroSynonyms(List.of("washington dc", "dc", "washington d.c.", "arlington va", "northern virginia", "nova")),
            new MetroSynonyms(List.of("houston", "houston tx")),
            new MetroSynonyms(List.of("raleigh", "durham", "research triangle", "rtp")),
            new MetroSynonyms(List.of("phoenix", "scottsdale", "tempe")),
            new MetroSynonyms(List.of("nashville", "nashville tn")),
            new MetroSynonyms(List.of("san diego", "sd")),
            new MetroSynonyms(List.of("minneapolis", "st paul", "twin cities"))
    );

    private record MetroSynonyms(List<String> terms) {
        boolean matches(String userLocation) {
            return terms.stream().anyMatch(term -> userLocation.contains(term));
        }
    }

    private boolean employmentTypeCompatible(String preferredType, String jobType) {
        String preferred = normalizedTermText(preferredType);
        String actual = normalizedTermText(jobType);
        if (preferred.isBlank() || actual.isBlank()) {
            return false;
        }

        if (preferred.equals(actual)) {
            return true;
        }

        // Normalize common variations
        if (preferred.contains("full") && (actual.contains("full") || actual.contains("permanent"))) return true;
        if (preferred.contains("contract") && (actual.contains("contract") || actual.contains("freelance"))) return true;
        if (preferred.contains("part") && actual.contains("part")) return true;
        if (preferred.contains("intern") && actual.contains("intern")) return true;

        return false;
    }

    /**
     * Compare a job's salary label (e.g., "$120k - $180k") against user expectations.
     * Returns positive score if salary overlaps user range, negative if too low.
     * Falls back to +3 if salary is listed but cannot be parsed.
     */
    private int scoreSalaryRangeFit(String salaryLabel, BigDecimal userMin, BigDecimal userMax) {
        if (salaryLabel == null || salaryLabel.isBlank()) {
            return 0;
        }

        // If user has no salary preferences, just reward having salary listed
        if (userMin == null && userMax == null) {
            return 3;
        }

        // Try to extract numeric salary from label like "$120,000 - $180,000" or "$120k - $180k"
        long jobMin = 0;
        long jobMax = 0;
        java.util.regex.Matcher matcher = SALARY_EXTRACT_PATTERN.matcher(salaryLabel);
        int found = 0;
        while (matcher.find() && found < 2) {
            long value = parseSalaryValue(matcher.group());
            if (found == 0) jobMin = value;
            jobMax = value;
            found++;
        }

        if (found == 0) {
            // Could not parse salary — still reward listing it
            return 3;
        }

        // If only one number found, treat as both min and max
        if (jobMin == 0) jobMin = jobMax;

        long expectMin = userMin != null ? userMin.longValue() : 0;
        long expectMax = userMax != null ? userMax.longValue() : Long.MAX_VALUE;

        // Job's max is above user's min AND job's min is below user's max = overlap
        if (jobMax >= expectMin && jobMin <= expectMax) {
            // Strong match: job range overlaps user range well
            if (jobMax >= expectMin && jobMin <= expectMax) {
                return 7;
            }
            return 5;
        }

        // Job pays less than user expects
        if (jobMax < expectMin && expectMin > 0) {
            long gap = expectMin - jobMax;
            if (gap > 30000) return -8;
            if (gap > 15000) return -4;
            return -1;
        }

        return 3;
    }

    private static final java.util.regex.Pattern SALARY_EXTRACT_PATTERN =
            java.util.regex.Pattern.compile("\\$?([\\d,]+\\.?\\d*\\s*[kK]?)");

    private long parseSalaryValue(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String cleaned = raw.replace("$", "").replace(",", "").trim();
        boolean isK = cleaned.toLowerCase(Locale.US).endsWith("k");
        if (isK) cleaned = cleaned.substring(0, cleaned.length() - 1);
        try {
            double value = Double.parseDouble(cleaned);
            if (isK || value < 1000) value *= 1000;
            return (long) value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean hasListedSalary(CandidateJobSummaryResponse job) {
        return !isSalaryMissing(job.getSalaryLabel());
    }

    private boolean isDirectSource(CandidateJobSummaryResponse job) {
        String sourceType = job.getSourceType() == null ? "" : job.getSourceType().trim().toUpperCase(Locale.US);
        return DIRECT_SOURCE_TYPES.contains(sourceType);
    }

    private boolean isRecent(OffsetDateTime sourceUpdatedAt, int maxAgeDays) {
        if (sourceUpdatedAt == null) {
            return false;
        }

        return !sourceUpdatedAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(maxAgeDays));
    }

    private String displayTerm(String term) {
        if (term == null || term.isBlank()) {
            return "";
        }

        String normalized = normalizedTermText(term);
        if (normalized.isBlank()) {
            return "";
        }

        return Pattern.compile(" ")
                .splitAsStream(normalized)
                .map(token -> token.length() <= 2
                        ? token.toUpperCase(Locale.US)
                        : token.substring(0, 1).toUpperCase(Locale.US) + token.substring(1))
                .reduce((left, right) -> left + " " + right)
                .orElse(normalized);
    }

    private String displayTerms(List<String> terms, int limit) {
        return terms.stream()
                .limit(limit)
                .map(this::displayTerm)
                .filter(value -> !value.isBlank())
                .toList()
                .stream()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String normalizedTermText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.toLowerCase(Locale.US)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9+#.]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String haystack, String... terms) {
        if (haystack == null || haystack.isBlank() || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && haystack.contains(term.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchText(String... values) {
        String joined = joinNonBlank(" ", values);
        return joined == null ? "" : joined.toLowerCase(Locale.US);
    }

    private Set<String> normalizedTerms(List<String> values) {
        Set<String> terms = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .map(this::normalizeTerm)
                    .filter(value -> value != null && value.length() > 1)
                    .forEach(terms::add);
        }
        return terms;
    }

    private String normalizeTerm(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.US);
    }

    private List<String> stringsFromJson(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return List.of();
        }

        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapFromJson(String value) {
        if (value == null || value.isBlank() || "{}".equals(value) || "null".equals(value)) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringsFromObject(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }

        return rawValues.stream()
                .filter(item -> item instanceof String)
                .map(item -> (String) item)
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    private Boolean booleanFromObject(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private record CandidateMatchContext(
            Set<String> skills,
            Set<String> targetRoles,
            Set<String> mustHaveSkills,
            Set<String> niceToHaveSkills,
            Set<String> avoidKeywords,
            String headline,
            String location,
            String preferredWorkMode,
            String preferredEmploymentType,
            Boolean needsSponsorship,
            Boolean needsSponsorshipNow,
            Boolean needsSponsorshipLater,
            Boolean requiresEVerify,
            Boolean openToCapExemptEmployers,
            Boolean openToRelocation,
            Boolean salaryRequired,
            Boolean easyApplyOnly,
            Boolean directCompanySourceOnly,
            Boolean noTakeHome,
            Boolean stabilityFirst,
            BigDecimal salaryExpectationMin,
            BigDecimal salaryExpectationMax,
            Integer yearsOfExperience) {

        private boolean isEmpty() {
            return (skills == null || skills.isEmpty())
                    && (targetRoles == null || targetRoles.isEmpty())
                    && (mustHaveSkills == null || mustHaveSkills.isEmpty())
                    && (niceToHaveSkills == null || niceToHaveSkills.isEmpty())
                    && (avoidKeywords == null || avoidKeywords.isEmpty())
                    && headline == null
                    && location == null
                    && preferredWorkMode == null
                    && preferredEmploymentType == null
                    && !Boolean.TRUE.equals(needsSponsorship)
                    && !Boolean.TRUE.equals(needsSponsorshipNow)
                    && !Boolean.TRUE.equals(needsSponsorshipLater)
                    && !Boolean.TRUE.equals(requiresEVerify)
                    && !Boolean.TRUE.equals(openToCapExemptEmployers)
                    && !Boolean.TRUE.equals(openToRelocation)
                    && !Boolean.TRUE.equals(salaryRequired)
                    && !Boolean.TRUE.equals(easyApplyOnly)
                    && !Boolean.TRUE.equals(directCompanySourceOnly)
                    && !Boolean.TRUE.equals(noTakeHome)
                    && !Boolean.TRUE.equals(stabilityFirst)
                    && salaryExpectationMin == null
                    && salaryExpectationMax == null
                    && yearsOfExperience == null;
        }
    }

    private record RankedJobsCacheEntry(
            int rankingLimit,
            List<CandidateJobSummaryResponse> jobs,
            Instant expiresAt) {
        private boolean isExpired(Instant now) {
            return expiresAt == null || now == null || !expiresAt.isAfter(now);
        }
    }

    private record VisaSignal(
            String sponsorshipLanguage,
            Integer visaConfidenceScore,
            List<String> reasons,
            Boolean requiresUsWorkAuthorization,
            Boolean contractOrStaffingRisk,
            Boolean stemOptRisk,
            Boolean h1bTransferFit,
            Boolean capExemptFit) {
    }

    private record MatchComponent(int score, List<String> reasons) {
        private static MatchComponent empty() {
            return new MatchComponent(0, List.of());
        }
    }

    private Comparator<CandidateJobSummaryResponse> personalizedJobComparator() {
        return Comparator
                .comparing(CandidateJobSummaryResponse::getMatchScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CandidateJobSummaryResponse::getJobQualityScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CandidateJobSummaryResponse::getSourceUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private Comparator<CandidateJobSummaryResponse> jobComparator() {
        return Comparator
                .comparing(CandidateJobSummaryResponse::getSourceUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CandidateJobSummaryResponse::getMatchScore, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private String dedupeKey(CandidateJobSummaryResponse job) {
        String url = firstNonBlank(job.getApplyUrl(), job.getJobUrl());
        if (url != null) {
            return "url:" + url.toLowerCase(Locale.US);
        }

        return "text:" + normalizeKey(job.getCompanyName()) + "|" + normalizeKey(job.getTitle()) + "|" + normalizeKey(job.getLocation());
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "ALL";
        }

        String normalized = source.trim().replace("-", "_").toUpperCase(Locale.US);
        return "SMART_RECRUITERS".equals(normalized) ? "SMARTRECRUITERS" : normalized;
    }

    private String resolveBoardToken(String boardToken) {
        if (boardToken != null && !boardToken.isBlank()) {
            return boardToken.trim().toLowerCase(Locale.US);
        }

        if (defaultGreenhouseBoard == null || defaultGreenhouseBoard.isBlank()) {
            throw new BadRequestException("No Greenhouse board configured");
        }

        return defaultGreenhouseBoard.trim().toLowerCase(Locale.US);
    }

    private List<String> configuredValues(String rawValue, String fallback) {
        String effective = rawValue == null || rawValue.isBlank() ? fallback : rawValue;
        if (effective == null || effective.isBlank()) {
            return List.of();
        }

        return Pattern.compile(",")
                .splitAsStream(effective)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        return Math.max(1, Math.min(limit, LIVE_SOURCE_LIMIT));
    }

    private int normalizeOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }

        return Math.max(0, offset);
    }

    private int normalizeMaxAgeDays(Integer maxAgeDays) {
        if (maxAgeDays == null) {
            return defaultMaxAgeDays;
        }

        if (maxAgeDays <= 0) {
            return defaultMaxAgeDays;
        }

        return Math.max(1, Math.min(maxAgeDays, defaultMaxAgeDays));
    }

    private Long parseGreenhouseJobId(String externalJobId) {
        try {
            return Long.parseLong(externalJobId);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Greenhouse job id must be numeric");
        }
    }

    private String sourceJobId(String sourceType, String boardToken, String externalJobId) {
        return sourceType.toLowerCase(Locale.US) + ":" + boardToken + ":" + externalJobId;
    }

    private String smartRecruitersLocation(SmartRecruitersPostingResponse.Posting posting) {
        SmartRecruitersPostingResponse.Location location = posting.getLocation();
        if (location == null) {
            return "Location not listed";
        }

        String locationLabel = firstNonBlank(
                location.getFullLocation(),
                joinNonBlank(", ", location.getCity(), location.getRegion(), smartRecruitersCountryLabel(location.getCountry())),
                "Location not listed");
        return appendCountryIfUseful(locationLabel, smartRecruitersCountryLabel(location.getCountry()));
    }

    private String smartRecruitersCountryLabel(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        return switch (country.trim().toLowerCase(Locale.US)) {
            case "us", "usa" -> "United States";
            default -> country.trim();
        };
    }

    private String smartRecruitersDepartment(SmartRecruitersPostingResponse.Posting posting) {
        return firstNonBlank(
                label(posting.getDepartment()),
                label(posting.getFunction()),
                label(posting.getIndustry()));
    }

    private String smartRecruitersWorkMode(SmartRecruitersPostingResponse.Posting posting, String locationLabel) {
        SmartRecruitersPostingResponse.Location location = posting.getLocation();
        if (location != null) {
            if (Boolean.TRUE.equals(location.getRemote())) {
                return "REMOTE";
            }
            if (Boolean.TRUE.equals(location.getHybrid())) {
                return "HYBRID";
            }
        }
        return inferWorkMode(posting.getName(), locationLabel);
    }

    private List<String> smartRecruitersExtraTags(SmartRecruitersPostingResponse.Posting posting) {
        List<String> tags = new ArrayList<>();
        tags.add(label(posting.getIndustry()));
        tags.add(label(posting.getFunction()));
        tags.add(label(posting.getTypeOfEmployment()));
        tags.add(label(posting.getExperienceLevel()));
        return tags;
    }

    private String smartRecruitersHostedUrl(String companyIdentifier, SmartRecruitersPostingResponse.Posting posting) {
        if (posting.getId() == null || posting.getId().isBlank()) {
            return null;
        }
        return "https://jobs.smartrecruiters.com/" + companyIdentifier + "/" + posting.getId();
    }

    private String smartRecruitersDescriptionHtml(SmartRecruitersPostingResponse.Posting posting) {
        SmartRecruitersPostingResponse.JobAdSections sections = posting.getJobAd() == null ? null : posting.getJobAd().getSections();
        if (sections == null) {
            return null;
        }

        List<String> chunks = new ArrayList<>();
        addSmartRecruitersSection(chunks, sections.getCompanyDescription());
        addSmartRecruitersSection(chunks, sections.getJobDescription());
        addSmartRecruitersSection(chunks, sections.getQualifications());
        addSmartRecruitersSection(chunks, sections.getAdditionalInformation());
        if (sections.getCustomSections() != null) {
            sections.getCustomSections().values().forEach(section -> addSmartRecruitersSection(chunks, section));
        }
        return chunks.isEmpty() ? null : String.join("", chunks);
    }

    private void addSmartRecruitersSection(List<String> chunks, SmartRecruitersPostingResponse.JobAdSection section) {
        if (section == null || section.getText() == null || section.getText().isBlank()) {
            return;
        }
        if (section.getTitle() != null && !section.getTitle().isBlank()) {
            chunks.add("<h3>" + escapeHtml(section.getTitle()) + "</h3>");
        }
        chunks.add(section.getText());
    }

    private String workableExternalJobId(WorkableJobBoardResponse.WorkableJob job) {
        String directId = firstNonBlank(job.getShortcode(), job.getCode());
        if (directId != null) {
            return directId;
        }

        String source = firstNonBlank(
                job.getUrl(),
                job.getApplicationUrl(),
                job.getShortlink(),
                joinNonBlank("|", job.getTitle(), job.getPublishedOn(), workableLocation(job)));
        String hashed = hash(firstNonBlank(source, "workable-job"));
        return firstNonBlank(hashed, normalizeKey(source));
    }

    private String workableLocation(WorkableJobBoardResponse.WorkableJob job) {
        String location = joinNonBlank(", ", job.getCity(), job.getState());
        location = appendCountryIfUseful(location, job.getCountry());
        return firstNonBlank(location, "Location not listed");
    }

    private String workableWorkMode(WorkableJobBoardResponse.WorkableJob job, String location) {
        if (Boolean.TRUE.equals(job.getTelecommuting())) {
            return "REMOTE";
        }
        String workplaceType = job.getWorkplaceType();
        if (workplaceType == null || workplaceType.isBlank()) {
            return inferWorkMode(job.getTitle(), location);
        }

        return switch (workplaceType.trim().toLowerCase(Locale.US).replace('_', '-')) {
            case "remote" -> "REMOTE";
            case "hybrid" -> "HYBRID";
            case "on-site", "onsite" -> "ONSITE";
            default -> inferWorkMode(workplaceType, location);
        };
    }

    private String workdayExternalJobId(String externalPath) {
        return encodeJobId(firstNonBlank(externalPath, "workday-job"));
    }

    private String workdayWorkMode(String remoteType, String location) {
        if (remoteType == null || remoteType.isBlank()) {
            return inferWorkMode(null, location);
        }

        return switch (remoteType.trim().toLowerCase(Locale.US)) {
            case "remote", "fully remote" -> "REMOTE";
            case "hybrid" -> "HYBRID";
            case "onsite", "on-site", "on site" -> "ONSITE";
            default -> inferWorkMode(remoteType, location);
        };
    }

    private String bambooHrLabel(BambooHrJobSummaryResponse.BambooHrLabel label) {
        return label == null ? null : label.getLabel();
    }

    private List<SchemaOrgJobPosting> extractSchemaOrgJobs(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        List<SchemaOrgJobPosting> jobs = new ArrayList<>();
        var matcher = JSON_LD_SCRIPT_PATTERN.matcher(html);
        while (matcher.find()) {
            String rawJson = decodeHtmlEntities(matcher.group(1)).trim();
            if (rawJson.isBlank()) {
                continue;
            }
            try {
                collectSchemaOrgJobs(objectMapper.readTree(rawJson), jobs);
            } catch (JsonProcessingException e) {
                log.debug("Skipping invalid career page JSON-LD block: {}", e.getMessage());
            }
        }
        return jobs;
    }

    private void collectSchemaOrgJobs(JsonNode node, List<SchemaOrgJobPosting> jobs) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectSchemaOrgJobs(child, jobs));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        if (node.has("@graph")) {
            collectSchemaOrgJobs(node.get("@graph"), jobs);
        }

        SchemaOrgJobPosting posting = schemaOrgFromNode(node);
        if (posting != null && posting.isJobPosting()) {
            jobs.add(posting);
        }
    }

    private SchemaOrgJobPosting schemaOrgFromNode(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, SchemaOrgJobPosting.class);
        } catch (JsonProcessingException e) {
            log.debug("Unable to map schema.org JobPosting block: {}", e.getMessage());
            return null;
        }
    }

    private String schemaOrgExternalJobId(SchemaOrgJobPosting job) {
        String source = firstNonBlank(
                job.getUrl(),
                joinNonBlank("|", job.getTitle(), schemaOrgOrganizationName(job.getHiringOrganization()), schemaValue(job.getDatePosted())));
        String hashed = hash(firstNonBlank(source, "schema-org-job"));
        return firstNonBlank(hashed, normalizeKey(source));
    }

    private String schemaOrgOrganizationName(Object organization) {
        if (organization instanceof List<?> values) {
            for (Object value : values) {
                String result = schemaOrgOrganizationName(value);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
        if (organization instanceof Map<?, ?> map) {
            return firstNonBlank(schemaValue(map.get("name")), schemaValue(map.get("legalName")), schemaValue(map.get("url")));
        }
        return schemaValue(organization);
    }

    private String schemaOrgLocation(Object location) {
        if (location instanceof List<?> values) {
            return values.stream()
                    .map(this::schemaOrgLocation)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        if (location instanceof Map<?, ?> map) {
            String name = schemaValue(map.get("name"));
            String address = schemaOrgAddress(map.get("address"));
            return firstNonBlank(joinNonBlank(", ", name, address), schemaValue(location));
        }
        return schemaValue(location);
    }

    private String schemaOrgAddress(Object address) {
        if (address instanceof List<?> values) {
            return values.stream()
                    .map(this::schemaOrgAddress)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        if (address instanceof Map<?, ?> map) {
            return firstNonBlank(
                    joinNonBlank(", ",
                            schemaValue(map.get("addressLocality")),
                            schemaValue(map.get("addressRegion")),
                            schemaOrgCountry(map.get("addressCountry"))),
                    schemaValue(address));
        }
        return schemaValue(address);
    }

    private String schemaOrgCountry(Object country) {
        if (country instanceof Map<?, ?> map) {
            return firstNonBlank(schemaValue(map.get("name")), schemaValue(map.get("addressCountry")));
        }
        return schemaValue(country);
    }

    private String schemaValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue.isBlank() ? null : stringValue.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof JsonNode node) {
            if (node.isTextual() || node.isNumber() || node.isBoolean()) {
                return node.asText();
            }
            if (node.isArray()) {
                for (JsonNode child : node) {
                    String nested = schemaValue(child);
                    if (nested != null) {
                        return nested;
                    }
                }
                return null;
            }
            if (node.isObject()) {
                return schemaValue(objectMapper.convertValue(node, Map.class));
            }
        }
        if (value instanceof List<?> values) {
            for (Object item : values) {
                String nested = schemaValue(item);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("name", "text", "value", "label", "descriptor", "title", "url")) {
                String nested = schemaValue(map.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            for (Object nestedValue : map.values()) {
                String nested = schemaValue(nestedValue);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private OffsetDateTime parseOffsetDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.ofInstant(Instant.parse(normalized), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(normalized.length() >= 10 ? normalized.substring(0, 10) : normalized)
                    .atStartOfDay()
                    .atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private OffsetDateTime parseRelativePostedLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.toLowerCase(Locale.US)
                .replace("posted", "")
                .replace("+", "")
                .trim();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (normalized.contains("today") || normalized.contains("just")) {
            return now;
        }
        if (normalized.contains("yesterday")) {
            return now.minusDays(1);
        }

        var dayMatcher = Pattern.compile("(\\d+)\\s+days?\\s+ago").matcher(normalized);
        if (dayMatcher.find()) {
            return now.minusDays(Integer.parseInt(dayMatcher.group(1)));
        }

        var weekMatcher = Pattern.compile("(\\d+)\\s+weeks?\\s+ago").matcher(normalized);
        if (weekMatcher.find()) {
            return now.minusWeeks(Integer.parseInt(weekMatcher.group(1)));
        }

        var monthMatcher = Pattern.compile("(\\d+)\\s+months?\\s+ago").matcher(normalized);
        if (monthMatcher.find()) {
            return now.minusMonths(Integer.parseInt(monthMatcher.group(1)));
        }

        return parseOffsetDate(value);
    }

    private String sourceDisplayName(String sourceType) {
        return switch (sourceType == null ? "" : sourceType.toUpperCase(Locale.US)) {
            case "JOBVITE" -> "Jobvite";
            case "ICIMS" -> "iCIMS";
            case "JAZZHR" -> "JazzHR";
            default -> formatCompanyName(sourceType);
        };
    }

    private String companyNameFromUrl(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(pageUrl).getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.replaceFirst("^www\\.", "");
            String[] parts = host.split("\\.");
            String candidate = parts.length > 1 && ("jobs".equals(parts[0]) || "careers".equals(parts[0]))
                    ? parts[1]
                    : parts[0];
            return formatCompanyName(normalizeKey(candidate));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String encodeJobId(String value) {
        return URLEncoder.encode(firstNonBlank(value, ""), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String decodeJobId(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private String extractSalaryLabel(String descriptionText) {
        if (descriptionText == null || descriptionText.isBlank()) {
            return null;
        }
        var matcher = SALARY_RANGE_PATTERN.matcher(descriptionText);
        return matcher.find() ? matcher.group().replaceAll("\\s+", " ").trim() : null;
    }

    private String label(SmartRecruitersPostingResponse.Label label) {
        return label == null ? null : label.getLabel();
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String locationName(GreenhouseJobBoardResponse.GreenhouseJob job) {
        if (job.getLocation() == null || job.getLocation().getName() == null || job.getLocation().getName().isBlank()) {
            return "Location not listed";
        }

        return job.getLocation().getName();
    }

    private String firstDepartment(GreenhouseJobBoardResponse.GreenhouseJob job) {
        if (job.getDepartments() == null || job.getDepartments().isEmpty()) {
            return null;
        }

        return job.getDepartments().get(0).getName();
    }

    private List<String> officeNames(GreenhouseJobBoardResponse.GreenhouseJob job) {
        if (job.getOffices() == null) {
            return List.of();
        }

        return job.getOffices().stream()
                .map(GreenhouseJobBoardResponse.GreenhouseOffice::getName)
                .filter(name -> name != null && !name.isBlank())
                .limit(2)
                .toList();
    }

    private String inferWorkMode(String title, String location) {
        String text = ((title == null ? "" : title) + " " + (location == null ? "" : location)).toLowerCase(Locale.US);
        if (text.contains("remote")) {
            return "REMOTE";
        }
        if (text.contains("hybrid")) {
            return "HYBRID";
        }
        return "ONSITE";
    }

    private String formatLeverWorkMode(String workplaceType, String location) {
        if (workplaceType == null || workplaceType.isBlank() || "unspecified".equalsIgnoreCase(workplaceType)) {
            return inferWorkMode(null, location);
        }

        return switch (workplaceType.trim().toLowerCase(Locale.US)) {
            case "remote" -> "REMOTE";
            case "hybrid" -> "HYBRID";
            case "on-site", "onsite" -> "ONSITE";
            default -> inferWorkMode(workplaceType, location);
        };
    }

    private String formatAshbyWorkMode(AshbyJobBoardResponse.AshbyJob job, String location) {
        if (Boolean.TRUE.equals(job.getIsRemote())) {
            return "REMOTE";
        }
        if (job.getWorkplaceType() == null || job.getWorkplaceType().isBlank()) {
            return inferWorkMode(job.getTitle(), location);
        }

        return switch (job.getWorkplaceType().trim().toLowerCase(Locale.US)) {
            case "remote" -> "REMOTE";
            case "hybrid" -> "HYBRID";
            case "onsite", "on-site" -> "ONSITE";
            default -> inferWorkMode(job.getTitle(), location);
        };
    }

    private Integer inferMatchScore(String title, String department) {
        String combined = ((title == null ? "" : title) + " " + (department == null ? "" : department)).toLowerCase(Locale.US);

        if (combined.contains("frontend") || combined.contains("front-end") || combined.contains("ui")) {
            return 74;
        }
        if (combined.contains("software") || combined.contains("engineer") || combined.contains("product")) {
            return 70;
        }
        return 64;
    }

    private Integer inferJobQualityScore(
            String salaryLabel,
            String location,
            OffsetDateTime sourceUpdatedAt,
            String applyUrl,
            String jobUrl,
            String department,
            String workMode,
            String descriptionText) {
        int score = 45;

        if (!isSalaryMissing(salaryLabel)) {
            score += 15;
        }
        if (location != null && !location.isBlank() && !"Location not listed".equalsIgnoreCase(location)) {
            score += 10;
        }
        if (sourceUpdatedAt != null) {
            long ageDays = Duration.between(sourceUpdatedAt.toInstant(), OffsetDateTime.now().toInstant()).toDays();
            score += ageDays <= 3 ? 10 : 5;
        }
        if (firstNonBlank(applyUrl, jobUrl) != null) {
            score += 5;
        }
        if (department != null && !department.isBlank()) {
            score += 5;
        }
        if (workMode != null && !workMode.isBlank() && !"UNKNOWN".equalsIgnoreCase(workMode)) {
            score += 5;
        }
        if (descriptionText != null && descriptionText.length() > 300) {
            score += 10;
        }

        return Math.min(95, score);
    }

    private List<String> buildQualityReasons(
            String salaryLabel,
            String location,
            OffsetDateTime sourceUpdatedAt,
            String applyUrl,
            String jobUrl,
            String department,
            String descriptionText) {
        List<String> reasons = new ArrayList<>();
        reasons.add(isSalaryMissing(salaryLabel) ? "Needs salary benchmark" : "Employer salary listed");
        if (location != null && !location.isBlank() && !"Location not listed".equalsIgnoreCase(location)) {
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

    private List<String> buildTags(String title, String department, String workMode, List<String> extraTags) {
        Set<String> tags = new LinkedHashSet<>();
        if (department != null && !department.isBlank()) {
            tags.add(department);
        }
        if ("REMOTE".equals(workMode)) {
            tags.add("Remote");
        } else if ("HYBRID".equals(workMode)) {
            tags.add("Hybrid");
        }

        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.US);
        if (normalizedTitle.contains("frontend") || normalizedTitle.contains("front-end")) {
            tags.add("Frontend");
        }
        if (normalizedTitle.contains("software") || normalizedTitle.contains("engineer")) {
            tags.add("Engineering");
        }
        if (extraTags != null) {
            extraTags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .forEach(tags::add);
        }

        return new ArrayList<>(tags).stream().limit(5).toList();
    }

    private String formatCompanyName(String boardToken) {
        if (boardToken == null || boardToken.isBlank()) {
            return "Company";
        }

        String[] parts = boardToken.replace('_', '-').split("-");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                words.add(part.substring(0, 1).toUpperCase(Locale.US) + part.substring(1));
            }
        }
        return String.join(" ", words);
    }

    private String formatPostedLabel(OffsetDateTime updatedAt) {
        if (updatedAt == null) {
            return "Date not listed";
        }

        Duration age = Duration.between(updatedAt.toInstant(), OffsetDateTime.now().toInstant());
        if (age.isNegative() || age.toHours() < 1) {
            return "Just updated";
        }
        if (age.toHours() < 24) {
            return age.toHours() + "h ago";
        }
        long days = age.toDays();
        if (days < 14) {
            return days + "d ago";
        }
        long weeks = Math.max(1, days / 7);
        return weeks + "w ago";
    }

    private GreenhouseJobBoardResponse.GreenhousePayRange firstPayRange(GreenhouseJobBoardResponse.GreenhouseJob job) {
        if (job.getPayInputRanges() == null || job.getPayInputRanges().isEmpty()) {
            return null;
        }

        return job.getPayInputRanges().get(0);
    }

    private BigDecimal centsToUnits(BigDecimal cents) {
        if (cents == null) {
            return null;
        }

        return cents.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private String formatSalary(GreenhouseJobBoardResponse.GreenhousePayRange payRange) {
        BigDecimal min = centsToUnits(payRange.getMinCents());
        BigDecimal max = centsToUnits(payRange.getMaxCents());
        String currency = payRange.getCurrencyType() == null ? "USD" : payRange.getCurrencyType();

        if (min == null && max == null) {
            return "Salary not listed";
        }
        if (min != null && max != null) {
            return currency + " " + compactMoney(min) + "-" + compactMoney(max);
        }
        return currency + " " + compactMoney(min == null ? max : min);
    }

    private String formatLeverSalary(LeverPostingResponse posting) {
        LeverPostingResponse.LeverSalaryRange salary = posting.getSalaryRange();
        if (salary != null && (salary.getMin() != null || salary.getMax() != null)) {
            String currency = salary.getCurrency() == null ? "USD" : salary.getCurrency();
            if (salary.getMin() != null && salary.getMax() != null) {
                return currency + " " + compactMoney(salary.getMin()) + "-" + compactMoney(salary.getMax());
            }
            return currency + " " + compactMoney(salary.getMin() == null ? salary.getMax() : salary.getMin());
        }

        return firstNonBlank(posting.getSalaryDescriptionPlain(), "Salary not listed");
    }

    private String formatAshbySalary(AshbyJobBoardResponse.AshbyJob job) {
        if (job.getCompensation() == null) {
            return "Salary not listed";
        }

        return firstNonBlank(
                job.getCompensation().getScrapeableCompensationSalarySummary(),
                job.getCompensation().getCompensationTierSummary(),
                "Salary not listed");
    }

    private AshbyJobBoardResponse.AshbyCompensationComponent ashbySalaryComponent(AshbyJobBoardResponse.AshbyJob job) {
        if (job.getCompensation() == null || job.getCompensation().getSummaryComponents() == null) {
            return null;
        }

        return job.getCompensation().getSummaryComponents().stream()
                .filter(component -> "Salary".equalsIgnoreCase(component.getCompensationType()))
                .findFirst()
                .orElse(null);
    }

    private String compactMoney(BigDecimal value) {
        if (value == null) {
            return "";
        }

        BigDecimal thousands = value.divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP);
        return "$" + thousands + "k";
    }

    private OffsetDateTime epochMillisToOffsetDateTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }

        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private String ashbyExternalJobId(AshbyJobBoardResponse.AshbyJob job) {
        String source = firstNonBlank(job.getJobUrl(), job.getApplyUrl(), job.getTitle() + "|" + job.getPublishedAt());
        String hashed = hash(source);
        return hashed == null ? normalizeKey(source) : hashed;
    }

    private String ashbyAddressLabel(AshbyJobBoardResponse.AshbyJob job) {
        AshbyJobBoardResponse.AshbyPostalAddress address = job.getAddress() == null ? null : job.getAddress().getPostalAddress();
        if (address == null) {
            return null;
        }

        return joinNonBlank(", ", address.getAddressLocality(), address.getAddressRegion());
    }

    private String ashbyCountry(AshbyJobBoardResponse.AshbyJob job) {
        AshbyJobBoardResponse.AshbyPostalAddress address = job.getAddress() == null ? null : job.getAddress().getPostalAddress();
        if (address != null && address.getAddressCountry() != null && !address.getAddressCountry().isBlank()) {
            return address.getAddressCountry();
        }
        if (job.getSecondaryLocations() == null) {
            return null;
        }

        return job.getSecondaryLocations().stream()
                .map(AshbyJobBoardResponse.AshbySecondaryLocation::getAddress)
                .filter(addressValue -> addressValue != null && addressValue.getAddressCountry() != null)
                .map(AshbyJobBoardResponse.AshbyPostalAddress::getAddressCountry)
                .findFirst()
                .orElse(null);
    }

    private String appendCountryIfUseful(String location, String country) {
        if (country == null || country.isBlank()) {
            return location;
        }

        String normalizedCountry = country.trim().equalsIgnoreCase("US") || country.trim().equalsIgnoreCase("USA")
                ? "United States"
                : country.trim();
        String normalizedLocation = location == null ? "" : location;
        if (normalizedLocation.toUpperCase(Locale.US).contains(normalizedCountry.toUpperCase(Locale.US))) {
            return normalizedLocation;
        }

        return normalizedLocation.isBlank() ? normalizedCountry : normalizedLocation + ", " + normalizedCountry;
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        return HTML_TAG_PATTERN.matcher(decodeHtmlEntities(html))
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String decodeHtmlEntities(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "-")
                .replace("&ndash;", "-")
                .replace("&rsquo;", "'")
                .replace("&lsquo;", "'")
                .replace("&rdquo;", "\"")
                .replace("&ldquo;", "\"");
    }

    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "Official posting. Open the role to review the full job description and apply on the employer site.";
        }

        return text.length() <= 220 ? text : text.substring(0, 217).trim() + "...";
    }

    private String firstListValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private List<String> compactList(String... values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private String joinNonBlank(String delimiter, String... values) {
        List<String> nonBlank = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                nonBlank.add(value.trim());
            }
        }

        return nonBlank.isEmpty() ? null : String.join(delimiter, nonBlank);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
