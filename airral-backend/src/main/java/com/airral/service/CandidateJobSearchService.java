package com.airral.service;

import com.airral.dto.ashby.AshbyJobBoardResponse;
import com.airral.dto.greenhouse.GreenhouseJobBoardResponse;
import com.airral.dto.lever.LeverPostingResponse;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.dto.smartrecruiters.SmartRecruitersPostingResponse;
import com.airral.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
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
import java.util.regex.Pattern;

@Service
public class CandidateJobSearchService {

    private static final Logger log = LoggerFactory.getLogger(CandidateJobSearchService.class);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern US_STATE_PATTERN = Pattern.compile(
            "(?i)(^|[\\s,(-])(AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|IA|ID|IL|IN|KS|KY|LA|MA|MD|ME|MI|MN|MO|MS|MT|NC|ND|NE|NH|NJ|NM|NV|NY|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VA|VT|WA|WI|WV|WY)([\\s,).]|$)"
    );
    private static final Pattern US_COUNTRY_PATTERN = Pattern.compile("(?i)(^|[^a-z0-9])u\\.?s\\.?a?([^a-z0-9]|$)");
    private static final Pattern SALARY_RANGE_PATTERN = Pattern.compile(
            "(?i)\\$\\s?\\d[\\d,]*(?:\\.\\d+)?\\s*(?:to|-|–)\\s*\\$?\\s?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*[A-Z]{3})?(?:\\s*(?:per|/)?\\s*(?:year|hour|annually))?"
    );
    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_MAX_AGE_DAYS = 60;
    private static final int LIVE_SOURCE_LIMIT = 500;

    private final ExternalJobPostingStore externalJobPostingStore;
    private final GreenhouseJobBoardClient greenhouseClient;
    private final LeverJobBoardClient leverClient;
    private final AshbyJobBoardClient ashbyClient;
    private final SmartRecruitersJobBoardClient smartRecruitersClient;
    private final String defaultGreenhouseBoard;
    private final String greenhouseBoards;
    private final String leverSites;
    private final String ashbyBoards;
    private final String smartRecruitersCompanies;
    private final String supportedCountry;
    private final int defaultMaxAgeDays;
    private final int maxLiveFallbackSources;
    private final int liveFallbackSourceConcurrency;

    public CandidateJobSearchService(
            ExternalJobPostingStore externalJobPostingStore,
            GreenhouseJobBoardClient greenhouseClient,
            LeverJobBoardClient leverClient,
            AshbyJobBoardClient ashbyClient,
            SmartRecruitersJobBoardClient smartRecruitersClient,
            @Value("${airral.jobs.greenhouse.default-board:airbnb}") String defaultGreenhouseBoard,
            @Value("${airral.jobs.greenhouse.board-tokens:airbnb}") String greenhouseBoards,
            @Value("${airral.jobs.lever.site-names:}") String leverSites,
            @Value("${airral.jobs.ashby.board-names:}") String ashbyBoards,
            @Value("${airral.jobs.smartrecruiters.company-identifiers:}") String smartRecruitersCompanies,
            @Value("${airral.jobs.country:US}") String supportedCountry,
            @Value("${airral.jobs.max-age-days:60}") int defaultMaxAgeDays,
            @Value("${airral.jobs.live-fallback.max-sources:12}") int maxLiveFallbackSources,
            @Value("${airral.jobs.live-fallback.source-concurrency:4}") int liveFallbackSourceConcurrency) {
        this.externalJobPostingStore = externalJobPostingStore;
        this.greenhouseClient = greenhouseClient;
        this.leverClient = leverClient;
        this.ashbyClient = ashbyClient;
        this.smartRecruitersClient = smartRecruitersClient;
        this.defaultGreenhouseBoard = defaultGreenhouseBoard;
        this.greenhouseBoards = greenhouseBoards;
        this.leverSites = leverSites;
        this.ashbyBoards = ashbyBoards;
        this.smartRecruitersCompanies = smartRecruitersCompanies;
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
        int resolvedMaxAgeDays = normalizeMaxAgeDays(maxAgeDays);
        return externalJobPostingStore.findRecommendedJobs(source, boardToken, limit, resolvedMaxAgeDays, query, company)
                .collectList()
                .flatMapMany(cachedJobs -> cachedJobs.isEmpty()
                        ? getLiveFallbackJobs(source, boardToken, limit, resolvedMaxAgeDays, query, company)
                        : Flux.fromIterable(cachedJobs));
    }

    public Mono<CandidateJobPageResponse> getRecommendedJobsPage(
            String source,
            String boardToken,
            Integer limit,
            Integer offset,
            Integer maxAgeDays,
            String query,
            String company) {
        int resolvedLimit = normalizeLimit(limit);
        int resolvedOffset = normalizeOffset(offset);
        int resolvedMaxAgeDays = normalizeMaxAgeDays(maxAgeDays);
        int queryLimit = Math.min(LIVE_SOURCE_LIMIT, resolvedLimit + 1);

        return externalJobPostingStore.findRecommendedJobs(source, boardToken, queryLimit, resolvedOffset, resolvedMaxAgeDays, query, company)
                .collectList()
                .flatMap(cachedJobs -> cachedJobs.isEmpty()
                        ? getLiveFallbackJobs(source, boardToken, Math.min(LIVE_SOURCE_LIMIT, resolvedOffset + queryLimit), resolvedMaxAgeDays, query, company)
                                .skip(resolvedOffset)
                                .take(queryLimit)
                                .collectList()
                        : Mono.just(cachedJobs))
                .map(jobs -> toJobPage(jobs, resolvedLimit, resolvedOffset));
    }

    public Flux<CandidateJobSummaryResponse> getLiveRecommendedJobs(
            String source,
            String boardToken,
            Integer limit,
            Integer maxAgeDays,
            String query,
            String company) {
        int resolvedLimit = normalizeLimit(limit);
        int resolvedMaxAgeDays = normalizeMaxAgeDays(maxAgeDays);
        List<Flux<CandidateJobSummaryResponse>> sourceStreams = recommendationSourceStreams(source, boardToken, resolvedLimit);

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

    public Flux<CandidateJobSummaryResponse> getGreenhouseRecommendedJobs(String boardToken, Integer limit) {
        return getLiveRecommendedJobs("greenhouse", boardToken, limit, DEFAULT_MAX_AGE_DAYS, null, null);
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

    private Mono<CandidateJobDetailResponse> loadExternalJobDetail(String normalizedSource, String boardToken, String externalJobId) {
        return switch (normalizedSource) {
            case "GREENHOUSE" -> getGreenhouseJobDetail(boardToken, parseGreenhouseJobId(externalJobId));
            case "LEVER" -> getLeverJobDetail(boardToken, externalJobId);
            case "ASHBY" -> getAshbyJobDetail(boardToken, externalJobId);
            case "SMARTRECRUITERS" -> getSmartRecruitersJobDetail(boardToken, externalJobId);
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

    private List<Flux<CandidateJobSummaryResponse>> recommendationSourceStreams(String source, String boardToken, int limit) {
        String normalizedSource = normalizeSource(source);
        List<Flux<CandidateJobSummaryResponse>> streams = new ArrayList<>();

        if ("ALL".equals(normalizedSource) || "GREENHOUSE".equals(normalizedSource)) {
            List<String> boards = boardToken == null || boardToken.isBlank()
                    ? configuredValues(greenhouseBoards, defaultGreenhouseBoard)
                    : List.of(boardToken);
            boards.forEach(board -> streams.add(safeSource("Greenhouse " + board, greenhouseSummaries(board, limit))));
        }

        if ("ALL".equals(normalizedSource) || "LEVER".equals(normalizedSource)) {
            List<String> sites = "LEVER".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(leverSites, null);
            sites
                    .forEach(site -> streams.add(safeSource("Lever " + site, leverSummaries(site, limit))));
        }

        if ("ALL".equals(normalizedSource) || "ASHBY".equals(normalizedSource)) {
            List<String> boards = "ASHBY".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(ashbyBoards, null);
            boards
                    .forEach(board -> streams.add(safeSource("Ashby " + board, ashbySummaries(board, limit))));
        }

        if ("ALL".equals(normalizedSource) || "SMARTRECRUITERS".equals(normalizedSource)) {
            List<String> companies = "SMARTRECRUITERS".equals(normalizedSource) && boardToken != null && !boardToken.isBlank()
                    ? List.of(boardToken)
                    : configuredValues(smartRecruitersCompanies, null);
            companies
                    .forEach(company -> streams.add(safeSource("SmartRecruiters " + company, smartRecruitersSummaries(company, limit))));
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
        return count;
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
        return detail;
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

        String tags = job.getTags() == null ? "" : String.join(" ", job.getTags());
        return containsNormalized(job.getTitle(), query)
                || containsNormalized(job.getCompanyName(), query)
                || containsNormalized(job.getLocation(), query)
                || containsNormalized(job.getDepartment(), query)
                || containsNormalized(job.getSourceName(), query)
                || containsNormalized(tags, query);
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
                || US_STATE_PATTERN.matcher(value).find();
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
            return 92;
        }
        if (combined.contains("software") || combined.contains("engineer") || combined.contains("product")) {
            return 86;
        }
        return 78;
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
