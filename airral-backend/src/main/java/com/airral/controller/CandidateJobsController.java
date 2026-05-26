package com.airral.controller;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.service.CandidateJobSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/candidate/jobs")
public class CandidateJobsController {

    private final CandidateJobSearchService candidateJobSearchService;

    public CandidateJobsController(CandidateJobSearchService candidateJobSearchService) {
        this.candidateJobSearchService = candidateJobSearchService;
    }

    @GetMapping("/recommended")
    public Mono<ResponseEntity<Flux<CandidateJobSummaryResponse>>> getRecommendedJobs(
            @RequestParam(value = "source", defaultValue = "all") String source,
            @RequestParam(value = "board", required = false) String boardToken,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "maxAgeDays", defaultValue = "60") Integer maxAgeDays,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "company", required = false) String company) {
        return Mono.just(ResponseEntity.ok(candidateJobSearchService.getRecommendedJobs(source, boardToken, limit, maxAgeDays, query, company)));
    }

    @GetMapping("/recommended/page")
    public Mono<ResponseEntity<CandidateJobPageResponse>> getRecommendedJobsPage(
            @RequestParam(value = "source", defaultValue = "all") String source,
            @RequestParam(value = "board", required = false) String boardToken,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset,
            @RequestParam(value = "maxAgeDays", defaultValue = "60") Integer maxAgeDays,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "company", required = false) String company) {
        return candidateJobSearchService.getRecommendedJobsPage(source, boardToken, limit, offset, maxAgeDays, query, company)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/source/{sourceType}/{boardToken}/{jobId}")
    public Mono<ResponseEntity<CandidateJobDetailResponse>> getExternalJobDetail(
            @PathVariable String sourceType,
            @PathVariable String boardToken,
            @PathVariable String jobId) {
        return candidateJobSearchService.getExternalJobDetail(sourceType, boardToken, jobId)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/greenhouse/{boardToken}/{jobId}")
    public Mono<ResponseEntity<CandidateJobDetailResponse>> getGreenhouseJobDetail(
            @PathVariable String boardToken,
            @PathVariable Long jobId) {
        return candidateJobSearchService.getExternalJobDetail("greenhouse", boardToken, String.valueOf(jobId))
                .map(ResponseEntity::ok);
    }
}
