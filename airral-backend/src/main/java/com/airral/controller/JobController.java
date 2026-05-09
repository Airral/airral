package com.airral.controller;

import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CreateJobRequest;
import com.airral.dto.response.JobResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class JobController {

    private final JobService jobService;
    private final JwtTokenProvider jwtTokenProvider;

    public JobController(JobService jobService, JwtTokenProvider jwtTokenProvider) {
        this.jobService = jobService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Create a new job
     * POST /api/jobs
     * Requires: HR_MANAGER or ADMIN role
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<JobResponse>> createJob(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return jobService.createJob(request, organizationId, userId)
                .map(job -> ResponseEntity.status(HttpStatus.CREATED).body(job));
    }

    /**
     * Get all jobs for the user's organization
     * GET /api/jobs
     */
    @GetMapping
    public Mono<ResponseEntity<Flux<JobResponse>>> getAllJobs(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(jobService.getAllJobs(organizationId)));
    }

    /**
     * Get open jobs (public - no auth required for job board)
     * GET /api/jobs/open
     */
    @GetMapping("/open")
    public Mono<ResponseEntity<Flux<JobResponse>>> getOpenJobs() {
        return Mono.just(ResponseEntity.ok(jobService.getOpenJobs()));
    }

    /**
     * Get job by ID
     * GET /api/jobs/{id}
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<JobResponse>> getJobById(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        // If no auth header, only allow access to open jobs
        if (authHeader == null) {
            return jobService.getOpenJobs()
                    .filter(job -> job.getId().equals(id))
                    .next()
                    .map(ResponseEntity::ok)
                    .defaultIfEmpty(ResponseEntity.notFound().build());
        }

        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return jobService.getJobById(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Update a job
     * PUT /api/jobs/{id}
     * Requires: HR_MANAGER or ADMIN role
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<JobResponse>> updateJob(
            @PathVariable Long id,
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return jobService.updateJob(id, request, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Delete a job
     * DELETE /api/jobs/{id}
     * Requires: HR_MANAGER or ADMIN role
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Void>> deleteJob(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return jobService.deleteJob(id, organizationId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /**
     * Get jobs by status
     * GET /api/jobs/status/{status}
     */
    @GetMapping("/status/{status}")
    public Mono<ResponseEntity<Flux<JobResponse>>> getJobsByStatus(
            @PathVariable String status,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        try {
            JobStatus jobStatus = JobStatus.valueOf(status.toUpperCase());
            return Mono.just(ResponseEntity.ok(
                    jobService.getJobsByStatus(organizationId, jobStatus)
            ));
        } catch (IllegalArgumentException e) {
            return Mono.error(new BadRequestException("Invalid job status: " + status));
        }
    }

    /**
     * Helper method to extract JWT token from Authorization header
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
