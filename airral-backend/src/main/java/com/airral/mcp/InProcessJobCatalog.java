package com.airral.mcp;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.service.CandidateJobSearchService;
import com.airral.service.ExplicitJobFilters;
import com.airral.service.ExternalJobPostingStore;

import reactor.core.publisher.Mono;

/**
 * The in-process implementation: the MCP endpoint lives in the API service, so
 * it can simply call the service that already does this work.
 *
 * <p>Every filter is a SQL predicate, applied before the limit is taken. This
 * used to fetch the newest {@code limit * 4} matches and narrow them in memory
 * -- the "among the newest few hundred" bug getRecommendedJobsPage had already
 * fixed for the web. On 51.7k local postings it turned "engineer", hybrid,
 * limit 25 into 2 postings, "nurse" in TX into 6 of 16 and "engineer" in London
 * into none of 19; on the live corpus "data analyst", remote, limit 25 gave 17,
 * and 7 when it was reported.
 *
 * <p>Without a location, a search is the website's signed-out page request,
 * parameter for parameter, so an agent asking for remote work and a person
 * clicking Remote get the same postings. UNKNOWN is claimed by no work-mode
 * filter on either path: the predicate is equality on the stored mode.
 */
@Component
public class InProcessJobCatalog implements JobCatalogPort {

    /**
     * Postings older than this are not interesting to a job seeker, and the
     * corpus refreshes every four hours.
     */
    private static final int MAX_AGE_DAYS = 60;

    private final CandidateJobSearchService candidateJobSearchService;
    private final ExternalJobPostingStore externalJobPostingStore;
    private final int locationMaxAgeDays;

    public InProcessJobCatalog(
            CandidateJobSearchService candidateJobSearchService,
            ExternalJobPostingStore externalJobPostingStore,
            @Value("${airral.jobs.max-age-days:60}") int configuredMaxAgeDays) {
        this.candidateJobSearchService = candidateJobSearchService;
        this.externalJobPostingStore = externalJobPostingStore;
        // Resolved the way CandidateJobSearchService resolves the page path's
        // window, so a location search never reaches older postings than the
        // same search without one.
        this.locationMaxAgeDays = configuredMaxAgeDays <= 0
                ? MAX_AGE_DAYS
                : Math.min(configuredMaxAgeDays, MAX_AGE_DAYS);
    }

    @Override
    public Mono<List<CandidateJobSummaryResponse>> search(
            String query, String location, String workMode, String company, boolean salaryListed, int limit) {
        // Sent only when wanted, as the web client sends it.
        Boolean salaryPosted = salaryListed ? Boolean.TRUE : null;

        if (!notBlank(location)) {
            return candidateJobSearchService
                    .getRecommendedJobsPage("all", null, limit, 0, MAX_AGE_DAYS,
                            blankToNull(query), blankToNull(company),
                            blankToNull(workMode), salaryPosted, null, null, null)
                    .map(page -> page.getJobs() == null ? List.<CandidateJobSummaryResponse>of() : page.getJobs());
        }

        // The page path has no location parameter, so this asks the store
        // directly, with the same filters and one more predicate. Signed out,
        // that query is all the page path does with database rows. Its live
        // fallback is not lost either: source=all skips it once more than 12
        // boards are configured, and the shipped board lists name dozens.
        return externalJobPostingStore
                .findRecommendedJobs("all", null, limit, 0, locationMaxAgeDays,
                        blankToNull(query), blankToNull(company),
                        new ExplicitJobFilters(blankToNull(workMode), salaryPosted, null, null),
                        location.trim())
                .collectList();
    }

    @Override
    public Mono<CandidateJobDetailResponse> detail(String sourceType, String boardToken, String externalJobId) {
        return candidateJobSearchService.getExternalJobDetail(sourceType, boardToken, externalJobId);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }
}
