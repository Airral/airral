package com.airral.controller;

import com.airral.dto.request.UpdateCandidateProfileRequest;
import com.airral.dto.response.CandidateProfileResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateProfileService;
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
    private final JwtTokenProvider jwtTokenProvider;

    public CandidateProfileController(CandidateProfileService profileService, JwtTokenProvider jwtTokenProvider) {
        this.profileService = profileService;
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
     * GET /api/candidate/profile/resume/{fileName}
     * Download the current user's stored resume file.
     */
    @GetMapping("/resume/{fileName:.+}")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<Resource>> getResume(
            @PathVariable String fileName,
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return profileService.getResume(email, fileName)
                .map(resource -> ResponseEntity.ok()
                        .contentType(profileService.mediaTypeForResume(fileName))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                        .body(resource));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
