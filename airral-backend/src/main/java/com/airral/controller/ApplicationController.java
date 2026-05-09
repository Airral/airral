package com.airral.controller;

import com.airral.domain.enums.ApplicationStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.dto.response.ApplicationResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.ApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/applications")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class ApplicationController {

    private final ApplicationService applicationService;
    private final JwtTokenProvider jwtTokenProvider;

    public ApplicationController(ApplicationService applicationService, JwtTokenProvider jwtTokenProvider) {
        this.applicationService = applicationService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Submit a new application (public - no auth required)
     * POST /api/applications
     */
    @PostMapping
    public Mono<ResponseEntity<ApplicationResponse>> submitApplication(
            @Valid @RequestBody SubmitApplicationRequest request) {
        
        return applicationService.submitApplication(request)
                .map(application -> ResponseEntity.status(HttpStatus.CREATED).body(application));
    }

    /**
     * Get all applications for the organization
     * GET /api/applications
     * Requires: HR_MANAGER, MANAGER, or ADMIN role
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ApplicationResponse>>> getAllApplications(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(applicationService.getAllApplications(organizationId)));
    }

    /**
     * Get application by ID
     * GET /api/applications/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<ApplicationResponse>> getApplicationById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return applicationService.getApplicationById(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Get applications for a specific job
     * GET /api/applications/job/{jobId}
     */
    @GetMapping("/job/{jobId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ApplicationResponse>>> getApplicationsByJob(
            @PathVariable Long jobId,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(
                applicationService.getApplicationsByJob(jobId, organizationId)
        ));
    }

    /**
     * Get my applications (as applicant)
     * GET /api/applications/applicant/{applicantId}
     */
    @GetMapping("/applicant/{applicantId}")
    @PreAuthorize("hasAuthority('APPLICANT')")
    public Mono<ResponseEntity<Flux<ApplicationResponse>>> getMyApplications(
            @PathVariable Long applicantId,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        // Security check: users can only see their own applications
        if (!userId.equals(applicantId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        return Mono.just(ResponseEntity.ok(
                applicationService.getMyApplications(applicantId)
        ));
    }

    /**
     * Update application status
     * PUT /api/applications/{id}/status?status=SHORTLISTED
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<ApplicationResponse>> updateApplicationStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        try {
            ApplicationStatus appStatus = ApplicationStatus.valueOf(status.toUpperCase());
            return applicationService.updateApplicationStatus(id, appStatus, organizationId, userId)
                    .map(ResponseEntity::ok);
        } catch (IllegalArgumentException e) {
            return Mono.error(new BadRequestException("Invalid application status: " + status));
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
