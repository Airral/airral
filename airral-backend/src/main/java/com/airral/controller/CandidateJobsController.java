package com.airral.controller;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateJobSearchService;
import com.airral.service.ExternalJobPostingStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
@RequestMapping("/api/candidate/jobs")
public class CandidateJobsController {

    private final CandidateJobSearchService candidateJobSearchService;
    private final ExternalJobPostingStore externalJobPostingStore;
    private final JwtTokenProvider jwtTokenProvider;

    public CandidateJobsController(
            CandidateJobSearchService candidateJobSearchService,
            ExternalJobPostingStore externalJobPostingStore,
            JwtTokenProvider jwtTokenProvider) {
        this.candidateJobSearchService = candidateJobSearchService;
        // Straight to the store rather than through CandidateJobSearchService:
        // this reads the shape of the corpus and takes no candidate context, so
        // there is nothing the search service would add to it.
        this.externalJobPostingStore = externalJobPostingStore;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * The role families we have live jobs for, largest first.
     *
     * <p>Onboarding renders this instead of a hardcoded list. Measured over the
     * live corpus, that list was 24 options and 100% of them tech or
     * white-collar, over a catalogue that is roughly 70% neither -- so the roles
     * offered have to be counted out of the postings we hold, or they drift back.
     *
     * <p>Unauthenticated, like every other GET under this path: these are
     * aggregate counts over jobs already public on the jobs page, and onboarding
     * needs them the moment the page opens. The response carries the unplaced
     * count too, which the page shows rather than hides -- 13.6% of postings fit
     * no family, and a candidate whose work is in that tail needs to know the
     * list is not the whole catalogue.
     *
     * <p>{@code max-age} is well short of the server-side hour so a browser can
     * never be more stale than the service is.
     */
    @GetMapping("/role-families")
    public Mono<ResponseEntity<ExternalJobPostingStore.RoleFamilyCatalog>> getRoleFamilies() {
        return externalJobPostingStore.findRoleFamilies()
                .map(catalog -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                        .body(catalog));
    }

    @GetMapping("/recommended")
    public Mono<ResponseEntity<Flux<CandidateJobSummaryResponse>>> getRecommendedJobs(
            @RequestParam(value = "source", defaultValue = "all") String source,
            @RequestParam(value = "board", required = false) String boardToken,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "maxAgeDays", defaultValue = "60") Integer maxAgeDays,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "company", required = false) String company,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String candidateEmail = candidateEmail(authHeader);
        return Mono.just(ResponseEntity.ok(candidateJobSearchService.getRecommendedJobs(source, boardToken, limit, maxAgeDays, query, company, candidateEmail)));
    }

    @GetMapping("/recommended/page")
    public Mono<ResponseEntity<CandidateJobPageResponse>> getRecommendedJobsPage(
            @RequestParam(value = "source", defaultValue = "all") String source,
            @RequestParam(value = "board", required = false) String boardToken,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset,
            @RequestParam(value = "maxAgeDays", defaultValue = "60") Integer maxAgeDays,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "company", required = false) String company,
            @RequestParam(value = "workMode", required = false) String workMode,
            @RequestParam(value = "salaryPosted", required = false) Boolean salaryPosted,
            @RequestParam(value = "experienceLevel", required = false) String experienceLevel,
            @RequestParam(value = "visaFriendly", required = false) Boolean visaFriendly,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String candidateEmail = candidateEmail(authHeader);
        return candidateJobSearchService.getRecommendedJobsPage(
                        source, boardToken, limit, offset, maxAgeDays, query, company,
                        workMode, salaryPosted, experienceLevel, visaFriendly, candidateEmail)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/source/{sourceType}/{boardToken}/{jobId}")
    public Mono<ResponseEntity<CandidateJobDetailResponse>> getExternalJobDetail(
            @PathVariable String sourceType,
            @PathVariable String boardToken,
            @PathVariable String jobId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String candidateEmail = candidateEmail(authHeader);
        return candidateJobSearchService.getExternalJobDetail(sourceType, boardToken, jobId, candidateEmail)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/greenhouse/{boardToken}/{jobId}")
    public Mono<ResponseEntity<CandidateJobDetailResponse>> getGreenhouseJobDetail(
            @PathVariable String boardToken,
            @PathVariable Long jobId) {
        return candidateJobSearchService.getExternalJobDetail("greenhouse", boardToken, String.valueOf(jobId))
                .map(ResponseEntity::ok);
    }

    private String candidateEmail(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }

        String token = authHeader.substring(7);
        try {
            return jwtTokenProvider.validateToken(token) ? jwtTokenProvider.getEmailFromToken(token) : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
