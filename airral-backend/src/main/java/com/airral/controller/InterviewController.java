package com.airral.controller;

import com.airral.dto.request.InterviewFeedbackRequest;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.dto.response.InterviewResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.InterviewService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/interviews")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class InterviewController {

    private final InterviewService interviewService;
    private final JwtTokenProvider jwtTokenProvider;

    public InterviewController(InterviewService interviewService, JwtTokenProvider jwtTokenProvider) {
        this.interviewService = interviewService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Schedule a new interview
     * POST /api/interviews
     * Requires: HR_MANAGER, MANAGER, or ADMIN role
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<InterviewResponse>> scheduleInterview(
            @Valid @RequestBody ScheduleInterviewRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return interviewService.scheduleInterview(request, organizationId, userId)
                .map(interview -> ResponseEntity.status(HttpStatus.CREATED).body(interview));
    }

    /**
     * Get all interviews for the organization
     * GET /api/interviews
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<InterviewResponse>>> getAllInterviews(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(interviewService.getAllInterviews(organizationId)));
    }

    /**
     * Get interviews by application
     * GET /api/interviews/application/{applicationId}
     */
    @GetMapping("/application/{applicationId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<Flux<InterviewResponse>>> getInterviewsByApplication(
            @PathVariable Long applicationId,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(
                interviewService.getInterviewsByApplication(applicationId, organizationId)
        ));
    }

    /**
     * Get upcoming interviews
     * GET /api/interviews/upcoming
     */
    @GetMapping("/upcoming")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<InterviewResponse>>> getUpcomingInterviews(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(interviewService.getUpcomingInterviews(organizationId)));
    }

    /**
     * Get interviews by date range (for calendar view)
     * GET /api/interviews/calendar?startDate=2026-04-01T00:00:00&endDate=2026-04-30T23:59:59
     */
    @GetMapping("/calendar")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<InterviewResponse>>> getInterviewsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(
                interviewService.getInterviewsByDateRange(organizationId, startDate, endDate)
        ));
    }

    /**
     * Submit interview feedback
     * PUT /api/interviews/{id}/feedback
     */
    @PutMapping("/{id}/feedback")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<InterviewResponse>> submitFeedback(
            @PathVariable Long id,
            @Valid @RequestBody InterviewFeedbackRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return interviewService.submitFeedback(id, request, organizationId)
                .map(ResponseEntity::ok);
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
