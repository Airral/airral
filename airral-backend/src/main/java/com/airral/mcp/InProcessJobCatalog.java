package com.airral.mcp;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.service.CandidateJobSearchService;

import reactor.core.publisher.Mono;

/**
 * The in-process implementation: the MCP endpoint lives in the API service, so
 * it can simply call the service that already does this work.
 *
 * <p>The filtering below happens here rather than in SQL on purpose. The
 * existing search already applies the full-text query, the company filter and
 * the freshness window in the database; location and work mode are narrowed
 * afterwards over a bounded result set. Pushing them down would mean new query
 * paths in a store that several endpoints share, for a tool nobody has used
 * yet. If search volume ever makes that fetch-then-filter wasteful, the fix is
 * a query parameter, not a rewrite of this class.
 */
@Component
public class InProcessJobCatalog implements JobCatalogPort {

    /**
     * Postings older than this are not interesting to a job seeker, and the
     * corpus refreshes every four hours.
     */
    private static final int MAX_AGE_DAYS = 60;

    /**
     * Over-fetch before narrowing, so filtering on location or work mode does
     * not silently return fewer results than asked for.
     */
    private static final int OVER_FETCH = 4;
    private static final int OVER_FETCH_CEILING = 200;

    private final CandidateJobSearchService candidateJobSearchService;

    public InProcessJobCatalog(CandidateJobSearchService candidateJobSearchService) {
        this.candidateJobSearchService = candidateJobSearchService;
    }

    @Override
    public Mono<List<CandidateJobSummaryResponse>> search(
            String query, String location, String workMode, String company, int limit) {

        boolean narrowing = notBlank(location) || notBlank(workMode);
        int fetch = narrowing ? Math.min(limit * OVER_FETCH, OVER_FETCH_CEILING) : limit;

        return candidateJobSearchService
                .getRecommendedJobs("all", null, fetch, MAX_AGE_DAYS, blankToNull(query), blankToNull(company))
                .filter(job -> matchesLocation(job, location))
                .filter(job -> matchesWorkMode(job, workMode))
                .take(limit)
                .collectList();
    }

    @Override
    public Mono<CandidateJobDetailResponse> detail(String sourceType, String boardToken, String externalJobId) {
        return candidateJobSearchService.getExternalJobDetail(sourceType, boardToken, externalJobId);
    }

    private boolean matchesLocation(CandidateJobSummaryResponse job, String location) {
        if (!notBlank(location)) {
            return true;
        }
        String actual = job.getLocation();
        return actual != null && actual.toLowerCase(Locale.ROOT).contains(location.toLowerCase(Locale.ROOT));
    }

    private boolean matchesWorkMode(CandidateJobSummaryResponse job, String workMode) {
        if (!notBlank(workMode)) {
            return true;
        }
        String actual = job.getWorkMode();
        return actual != null && actual.equalsIgnoreCase(workMode.trim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }
}
