package com.airral.service;

import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.dto.workday.WorkdayJobSearchResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who gets to choose the host the live fallback calls.
 *
 * <p>GET /api/candidate/jobs/** is permitAll, and both recommendation endpoints
 * take source and board from the query string. The fallback runs only when the
 * database matched nothing, which an unrecognised board token guarantees, and
 * the gate in front of it used to answer "one source, fine" for any token on a
 * named source. That handed an anonymous caller the outbound host: a WORKDAY
 * token is host|tenant|site and is POSTed to, and a JOBVITE/ICIMS/JAZZHR token
 * is fetched as a whole URL that only has to start with https://.
 *
 * <p>So these tests assert on whether the board client is touched at all, not
 * on what comes back. Reaching the network is the act being prevented, and a
 * refusal that still made the request would pass an assertion about the empty
 * response body.
 */
class LiveFallbackSsrfTest {

    /** The one Workday source this deployment configured, in the same shape application.yml uses. */
    private static final String CONFIGURED_WORKDAY = "acme.wd5.myworkdayjobs.com|acme|Careers";
    private static final String CONFIGURED_JOBVITE = "https://jobs.jobvite.com/acme/jobs";

    private final ExternalJobPostingStore store = mock(ExternalJobPostingStore.class);
    private final WorkdayJobBoardClient workdayClient = mock(WorkdayJobBoardClient.class);
    private final CareerPageJobBoardClient careerPageClient = mock(CareerPageJobBoardClient.class);
    private final GreenhouseJobBoardClient greenhouseClient = mock(GreenhouseJobBoardClient.class);
    private final LeverJobBoardClient leverClient = mock(LeverJobBoardClient.class);
    private final BambooHrJobBoardClient bambooHrClient = mock(BambooHrJobBoardClient.class);

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            store,
            greenhouseClient,
            leverClient,
            mock(AshbyJobBoardClient.class),
            mock(SmartRecruitersJobBoardClient.class),
            mock(WorkableJobBoardClient.class),
            workdayClient,
            bambooHrClient,
            careerPageClient,
            mock(CandidateProfileRepository.class),
            mock(UserRepository.class),
            new ObjectMapper(),
            "airbnb",
            "airbnb,figma",
            "matchgroup",
            "",
            "",
            "",
            CONFIGURED_WORKDAY,
            CONFIGURED_JOBVITE,
            "",
            "",
            "",
            "US",
            45,
            12,
            4
    );

    /** Nothing stored for this board, which is what makes the fallback fire. */
    private List<CandidateJobSummaryResponse> recommended(String source, String board) {
        when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any())).thenReturn(Flux.empty());
        List<CandidateJobSummaryResponse> jobs = service
                .getRecommendedJobs(source, board, 5, 30, null, null)
                .collectList()
                .block();
        return jobs == null ? List.of() : jobs;
    }

    private boolean allowed(String source, String board) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(service, "liveFallbackAllowed", source, board));
    }

    @Test
    @DisplayName("a Workday token naming a host we never configured is refused")
    void foreignWorkdayHostIsRefused() {
        assertThat(recommended("WORKDAY", "metadata.internal|acme|Careers")).isEmpty();
        assertThat(allowed("WORKDAY", "metadata.internal|acme|Careers")).isFalse();
        verifyNoInteractions(workdayClient);
    }

    @Test
    @DisplayName("swapping only the host of a configured Workday token is still refused")
    void workdayTenantAndSiteAreNotEnough() {
        // The tenant and site here are the real ones; only the host moved. Left
        // to the sync's own connectors those two halves look configured, so the
        // whole token has to match or the host is still the caller's to pick.
        assertThat(allowed("WORKDAY", "10.128.0.7|acme|Careers")).isFalse();
        assertThat(recommended("WORKDAY", "10.128.0.7|acme|Careers")).isEmpty();
        verifyNoInteractions(workdayClient);
    }

    @Test
    @DisplayName("a whole URL smuggled in as a Jobvite board is refused")
    void foreignCareerPageUrlIsRefused() {
        assertThat(recommended("JOBVITE", "https://169.254.169.254/computeMetadata/v1/")).isEmpty();
        assertThat(allowed("JOBVITE", "https://internal-admin.svc.cluster.local/")).isFalse();
        verifyNoInteractions(careerPageClient);
    }

    @Test
    @DisplayName("a source with no configured list of its own cannot be used to reach out")
    void unknownSourceIsRefused() {
        assertThat(allowed("RECRUITEE", "https://recruitee.example.com/careers")).isFalse();
        assertThat(allowed("../../etc", "anything")).isFalse();
        // ICIMS and JAZZHR are real connectors, but this deployment configured
        // no pages for them, so there is nothing a token can legitimately name.
        assertThat(allowed("ICIMS", "https://careers-acme.icims.com/jobs")).isFalse();
        assertThat(allowed("JAZZHR", "https://acme.applytojob.com/apply")).isFalse();
        assertThat(recommended("ICIMS", "https://careers-acme.icims.com/jobs")).isEmpty();
        verifyNoInteractions(careerPageClient);
    }

    @Test
    @DisplayName("a BambooHR token is refused, and that branch would have carried our API key")
    void bambooHrHostSplicingIsRefused() {
        // Worth its own case because it is the worst of the ten and was not the
        // one the report named. BambooHrJobBoardClient builds its URL as
        // "https://" + token + ".bamboohr.com/api/v1/...", so a token of
        // "evil.example.com/collect?a=" parses to host evil.example.com and the
        // request carries Authorization: Basic <BAMBOOHR_API_KEY>. Every other
        // branch merely makes a call; this one hands the key over and does not
        // need to read the reply.
        assertThat(allowed("BAMBOOHR", "evil.example.com/collect?a=")).isFalse();
        assertThat(recommended("BAMBOOHR", "evil.example.com/collect?a=")).isEmpty();
        verifyNoInteractions(bambooHrClient);
    }

    @Test
    @DisplayName("no named source lets an unconfigured token through")
    void everyNamedSourceRefusesAnUnconfiguredToken() {
        // The gate switches on the same normalised names recommendationSourceStreams
        // branches on, and nothing but this list keeps the two in step. A connector
        // added to the streams and forgotten in the gate falls to the switch default
        // and is reachable with a token nobody configured, which is the shape of the
        // hole being closed. Naming all ten here fails that addition instead.
        for (String source : List.of(
                "greenhouse", "lever", "ashby", "smartrecruiters", "workable",
                "workday", "bamboohr", "jobvite", "icims", "jazzhr")) {
            assertThat(allowed(source, "https://169.254.169.254/x|tenant|site"))
                    .as("source %s", source)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a configured Workday board still reaches the fallback")
    void configuredWorkdayBoardStillFetches() {
        when(workdayClient.listJobs(any(), anyInt(), anyInt(), any()))
                .thenReturn(Mono.just(new WorkdayJobSearchResponse()));

        assertThat(recommended("WORKDAY", CONFIGURED_WORKDAY)).isEmpty();

        verify(workdayClient).listJobs(
                eq(WorkdayJobBoardClient.WorkdaySource.parse(CONFIGURED_WORKDAY)), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("a configured Jobvite page still reaches the fallback")
    void configuredCareerPageStillFetches() {
        when(careerPageClient.fetchPage(any(), any())).thenReturn(Mono.just(""));

        assertThat(recommended("JOBVITE", CONFIGURED_JOBVITE)).isEmpty();

        verify(careerPageClient).fetchPage(CONFIGURED_JOBVITE, "Jobvite");
    }

    @Test
    @DisplayName("the configured token is matched the way the connectors normalise it")
    void configuredTokenMatchIgnoresCaseAndPadding() {
        // resolveBoardToken lowercases and every other summary path trims, so a
        // link that arrives with a capital or a stray space is the same board.
        assertThat(allowed("GREENHOUSE", "  Figma ")).isTrue();
        assertThat(allowed("LEVER", "MATCHGROUP")).isTrue();
        assertThat(allowed("WORKDAY", CONFIGURED_WORKDAY.toUpperCase(java.util.Locale.US))).isTrue();
        // Prefixes and suffixes are not the same board.
        assertThat(allowed("GREENHOUSE", "figma.evil.example.com")).isFalse();
        assertThat(allowed("LEVER", "matchgroup/../rover")).isFalse();
    }

    @Test
    @DisplayName("no board token at all is still the ordinary configured sweep")
    void blankBoardIsUnaffected() {
        assertThat(allowed("GREENHOUSE", null)).isTrue();
        assertThat(allowed("GREENHOUSE", "  ")).isTrue();
        assertThat(allowed(null, null)).isTrue();
        // What the portal and the website actually send.
        assertThat(allowed("all", null)).isTrue();
        verify(greenhouseClient, never()).listJobs(any());
    }
}
