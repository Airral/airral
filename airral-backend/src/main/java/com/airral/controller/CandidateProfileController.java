package com.airral.controller;

import com.airral.dto.request.UpdateCandidateProfileRequest;
import com.airral.dto.response.CandidateProfileResponse;
import com.airral.dto.response.ResumeHealthResponse;
import com.airral.dto.response.ResumeReviewResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateProfileService;
import com.airral.service.ResumeHealthScoreService;
import com.airral.service.ResumeHealthScoreService.ResumeHealthResult;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/candidate/profile")
public class CandidateProfileController {

    private final CandidateProfileService profileService;
    private final ResumeHealthScoreService resumeHealthScoreService;
    private final JwtTokenProvider jwtTokenProvider;

    public CandidateProfileController(CandidateProfileService profileService,
                                      ResumeHealthScoreService resumeHealthScoreService,
                                      JwtTokenProvider jwtTokenProvider) {
        this.profileService = profileService;
        this.resumeHealthScoreService = resumeHealthScoreService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * GET /api/candidate/profile
     * Returns the current user's rich profile. Auto-creates it if this is their first visit.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<CandidateProfileResponse>> getProfile(
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.getOrCreateProfile(email)
                .map(ResponseEntity::ok);
    }

    /**
     * PUT /api/candidate/profile
     * Update the current user's profile. Partial updates supported (null fields are ignored).
     */
    @PutMapping
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<CandidateProfileResponse>> updateProfile(
            @RequestBody UpdateCandidateProfileRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.updateProfile(email, request)
                .map(ResponseEntity::ok);
    }

    /**
     * POST /api/candidate/profile/resume
     * Upload a PDF or DOCX resume and attach it to the current candidate profile.
     */
    @PostMapping(value = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<CandidateProfileResponse>> uploadResume(
            @RequestPart("file") FilePart file,
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.uploadResume(email, file)
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/candidate/profile/resume/review
     * Returns parsed resume data for user review. After uploading a resume, the frontend
     * calls this to show extracted skills, experience, and suggested target roles so the
     * user can confirm or edit before the system uses them for job matching.
     */
    @GetMapping("/resume/review")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<ResumeReviewResponse>> getResumeReview(
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.getResumeReview(email)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/candidate/profile/resume/document/{documentId}
     * Download the current user's stored resume document.
     */
    @GetMapping("/resume/document/{documentId}")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<Resource>> getResumeDocument(
            @PathVariable Long documentId,
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.getResume(email, documentId)
                .map(download -> ResponseEntity.ok()
                        .contentType(download.mediaType())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                        .body(download.resource()));
    }

    /**
     * GET /api/candidate/profile/resume/{fileName}
     * Legacy download route for older resume URLs.
     */
    @GetMapping("/resume/{fileName:.+}")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<Resource>> getResume(
            @PathVariable String fileName,
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.getResume(email, fileName)
                .map(resource -> ResponseEntity.ok()
                        .contentType(resource.mediaType())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.fileName() + "\"")
                        .body(resource.resource()));
    }

    /**
     * GET /api/candidate/profile/resume/health
     * Returns an instant resume health score with category breakdown, issues, and top fixes.
     */
    @GetMapping("/resume/health")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<ResumeHealthResponse>> getResumeHealth(
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.getActiveResumeDocument(email)
                .map(resumeHealthScoreService::analyze)
                .map(result -> ResumeHealthResponse.builder()
                        .score(result.score())
                        .grade(result.grade())
                        .categories(result.categories())
                        .issues(result.issues())
                        .topFixes(result.topFixes())
                        .wordCount(result.wordCount())
                        .skillCount(result.skillCount())
                        .build())
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
