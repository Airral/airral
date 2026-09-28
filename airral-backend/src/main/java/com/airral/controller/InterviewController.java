package com.airral.controller;

import com.airral.dto.request.InterviewFeedbackRequest;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.dto.request.ScorecardRequest;
import com.airral.dto.response.InterviewResponse;
import com.airral.dto.response.ScorecardResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateProfileService;
import com.airral.service.InterviewService;
import com.airral.service.JobScope;
import com.airral.service.HiringScope;
import com.airral.service.ScorecardService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;
    private final HiringScope hiringScope;
    private final JwtTokenProvider jwtTokenProvider;
    private final ScorecardService scorecardService;
    private final CandidateProfileService candidateProfileService;

    public InterviewController(InterviewService interviewService, JwtTokenProvider jwtTokenProvider,
                                 HiringScope hiringScope, ScorecardService scorecardService,
                                 CandidateProfileService candidateProfileService) {
        this.hiringScope = hiringScope;
        this.interviewService = interviewService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.scorecardService = scorecardService;
        this.candidateProfileService = candidateProfileService;
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

        return scopeFor(token).flatMap(scope -> interviewService.scheduleInterview(request, organizationId, userId, scope))
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

        return Mono.just(ResponseEntity.ok(scopeFor(token).flatMapMany(scope ->
                interviewService.getAllInterviews(organizationId, scope))));
    }

    /**
     * The interviews the caller is on as an interviewer.
     * GET /api/interviews/mine
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Flux<InterviewResponse>>> getMyInterviews(
            @RequestHeader("Authorization") String authHeader) {

        String token = extractToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        return Mono.just(ResponseEntity.ok(scorecardService.withMyScorecardStatus(
                interviewService.getMyInterviews(userId, jwtTokenProvider.getOrganizationIdFromToken(token)),
                userId)));
    }

    /**
     * The caller's own scorecard for an interview they are on.
     * GET /api/interviews/{id}/scorecard
     */
    @GetMapping("/{id}/scorecard")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<ScorecardResponse>> getMyScorecard(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {

        String token = extractToken(authHeader);
        return scorecardService.myScorecard(id, jwtTokenProvider.getUserIdFromToken(token),
                        jwtTokenProvider.getOrganizationIdFromToken(token))
                .map(ResponseEntity::ok);
    }

    /**
     * Save the caller's scorecard as a draft, or submit it.
     * PUT /api/interviews/{id}/scorecard
     */
    @PutMapping("/{id}/scorecard")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<ScorecardResponse>> saveMyScorecard(
            @PathVariable Long id,
            @Valid @RequestBody ScorecardRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String token = extractToken(authHeader);
        return scorecardService.saveMyScorecard(id, jwtTokenProvider.getUserIdFromToken(token),
                        jwtTokenProvider.getOrganizationIdFromToken(token), request)
                .map(ResponseEntity::ok);
    }

    /**
     * The resume of the candidate in an interview the caller is on.
     * GET /api/interviews/{id}/resume
     */
    @GetMapping("/{id}/resume")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Resource>> getInterviewResume(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {

        String token = extractToken(authHeader);
        return scorecardService.applicationWithResumeForInterviewer(id, jwtTokenProvider.getUserIdFromToken(token),
                        jwtTokenProvider.getOrganizationIdFromToken(token))
                .flatMap(application -> candidateProfileService.getApplicationResume(
                        application.getApplicantId(), application.getResumeDocumentId()))
                .map(download -> ResponseEntity.ok()
                        .contentType(download.mediaType())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                        .body(download.resource()));
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
                scopeFor(token).flatMapMany(scope ->
                        interviewService.getInterviewsByApplication(applicationId, organizationId, scope))
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

        return Mono.just(ResponseEntity.ok(scopeFor(token).flatMapMany(scope ->
                interviewService.getUpcomingInterviews(organizationId, scope))));
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
                scopeFor(token).flatMapMany(scope ->
                        interviewService.getInterviewsByDateRange(organizationId, startDate, endDate, scope))
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

        return scopeFor(token).flatMap(scope -> interviewService.submitFeedback(id, request, organizationId, scope))
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

    /** The jobs this caller may work on: all of the company's, or a hiring manager's own. */
    private Mono<JobScope> scopeFor(String token) {
        return hiringScope.of(jwtTokenProvider.getOrganizationIdFromToken(token),
                jwtTokenProvider.getUserIdFromToken(token),
                jwtTokenProvider.getRoleFromToken(token));
    }
}
