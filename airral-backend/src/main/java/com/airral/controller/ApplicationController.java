package com.airral.controller;

import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.dto.response.ApplicationResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.ApplicationService;
import com.airral.service.JobScope;
import com.airral.service.HiringScope;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final HiringScope hiringScope;
    private final JwtTokenProvider jwtTokenProvider;

    public ApplicationController(ApplicationService applicationService, JwtTokenProvider jwtTokenProvider,
                                 HiringScope hiringScope) {
        this.hiringScope = hiringScope;
        this.applicationService = applicationService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Create an application
     * POST /api/applications
     *
     * <p>An applicant applies for themselves, and HR adds a candidate to one
     * of its own company's jobs. Nobody else creates applications, and nobody
     * files one under another person's account.
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<ApplicationResponse>> submitApplication(
            @Valid @RequestBody SubmitApplicationRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String token = extractToken(authHeader);
        String role = jwtTokenProvider.getRoleFromToken(token);

        Mono<ApplicationResponse> created;
        if (UserRole.APPLICANT.name().equals(role)) {
            created = applicationService.applyAsApplicant(request,
                    jwtTokenProvider.getUserIdFromToken(token),
                    jwtTokenProvider.getEmailFromToken(token));
        } else if (UserRole.HR_MANAGER.name().equals(role) || UserRole.ADMIN.name().equals(role)) {
            created = applicationService.addCandidate(request,
                    jwtTokenProvider.getOrganizationIdFromToken(token));
        } else {
            // @PreAuthorize already turns these away; this keeps the rule true
            // if the annotation is ever dropped.
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        return created.map(application -> ResponseEntity.status(HttpStatus.CREATED).body(application));
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

        return Mono.just(ResponseEntity.ok(scopeFor(token).flatMapMany(scope ->
                applicationService.getAllApplications(organizationId, scope))));
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

        return scopeFor(token).flatMap(scope -> applicationService.getApplicationById(id, organizationId, scope))
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
                scopeFor(token).flatMapMany(scope -> applicationService.getApplicationsByJob(jobId, organizationId, scope))
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
            return scopeFor(token).flatMap(scope ->
                            applicationService.updateApplicationStatus(id, appStatus, organizationId, userId, scope))
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

    /** The jobs this caller may work on: all of the company's, or a hiring manager's own. */
    private Mono<JobScope> scopeFor(String token) {
        return hiringScope.of(jwtTokenProvider.getOrganizationIdFromToken(token),
                jwtTokenProvider.getUserIdFromToken(token),
                jwtTokenProvider.getRoleFromToken(token));
    }
}
