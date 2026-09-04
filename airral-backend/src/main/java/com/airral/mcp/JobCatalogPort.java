package com.airral.mcp;

import java.util.List;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;

import reactor.core.publisher.Mono;

/**
 * Everything the MCP tools need from the job catalogue, and nothing else.
 *
 * <p>This interface exists to be the seam. The MCP endpoint currently runs
 * inside the API service, which is the right call while the binding constraint
 * is a 25-connection database ceiling and a cold start on every scale-from-zero.
 * It is not where this ends up: every vendor shipping MCP publicly runs it as
 * its own deployable, and the trigger to follow them is building the OAuth and
 * token-exchange layer that makes a separate service able to authenticate at
 * all.
 *
 * <p>When that happens, a separate service will have no Spring beans from this
 * application and no database of its own to reach. If the tools called
 * {@code CandidateJobSearchService} directly, that would be a rewrite. Because
 * they call this instead, it is one new implementation that speaks HTTP, and the
 * tool code does not change.
 *
 * <p>Deliberately not a loopback HTTP client today. That would look
 * future-proof while paying serialisation and a second pass through the filter
 * chain on every single call, forever, to avoid writing one interface once.
 */
public interface JobCatalogPort {

    /**
     * Free-text search over active postings.
     *
     * @param query    words to match against title, tags and description
     * @param location optional substring filter, null for anywhere
     * @param workMode REMOTE / HYBRID / ONSITE, null for any
     * @param limit    hard cap on results, so a tool call cannot return a corpus
     */
    Mono<List<CandidateJobSummaryResponse>> search(
            String query, String location, String workMode, String company, int limit);

    /** One posting in full, addressed the way external jobs are keyed. */
    Mono<CandidateJobDetailResponse> detail(String sourceType, String boardToken, String externalJobId);
}
