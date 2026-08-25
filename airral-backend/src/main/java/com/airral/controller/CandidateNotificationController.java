package com.airral.controller;

import com.airral.dto.request.UpdateNotificationPreferencesRequest;
import com.airral.dto.response.NotificationPreferencesResponse;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateEmailService;
import com.airral.exception.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/candidate/notifications")
public class CandidateNotificationController {

    private final CandidateEmailService emailService;
    private final JwtTokenProvider jwtTokenProvider;

    public CandidateNotificationController(CandidateEmailService emailService, JwtTokenProvider jwtTokenProvider) {
        this.emailService = emailService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * GET /api/candidate/notifications/preferences
     * Returns the current user's notification preferences.
     */
    @GetMapping("/preferences")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<NotificationPreferencesResponse>> getPreferences(
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return emailService.getOrCreatePreferences(email)
                .map(pref -> NotificationPreferencesResponse.builder()
                        .jobAlertEnabled(pref.getJobAlertEnabled())
                        .followUpReminderEnabled(pref.getFollowUpReminderEnabled())
                        .weeklyDigestEnabled(pref.getWeeklyDigestEnabled())
                        .resumeNudgeEnabled(pref.getResumeNudgeEnabled())
                        .savedJobChangeEnabled(pref.getSavedJobChangeEnabled())
                        .build())
                .map(ResponseEntity::ok);
    }

    /**
     * PUT /api/candidate/notifications/preferences
     * Update notification preferences.
     */
    @PutMapping("/preferences")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<NotificationPreferencesResponse>> updatePreferences(
            @RequestBody UpdateNotificationPreferencesRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String email = jwtTokenProvider.getEmailFromToken(extractToken(authHeader));
        return emailService.getOrCreatePreferences(email)
                .flatMap(pref -> {
                    if (request.getJobAlertEnabled() != null) pref.setJobAlertEnabled(request.getJobAlertEnabled());
                    if (request.getFollowUpReminderEnabled() != null) pref.setFollowUpReminderEnabled(request.getFollowUpReminderEnabled());
                    if (request.getWeeklyDigestEnabled() != null) pref.setWeeklyDigestEnabled(request.getWeeklyDigestEnabled());
                    if (request.getResumeNudgeEnabled() != null) pref.setResumeNudgeEnabled(request.getResumeNudgeEnabled());
                    if (request.getSavedJobChangeEnabled() != null) pref.setSavedJobChangeEnabled(request.getSavedJobChangeEnabled());
                    pref.setUpdatedAt(OffsetDateTime.now());
                    return emailService.getOrCreatePreferences(email); // re-fetch after save via repository
                })
                .map(pref -> NotificationPreferencesResponse.builder()
                        .jobAlertEnabled(pref.getJobAlertEnabled())
                        .followUpReminderEnabled(pref.getFollowUpReminderEnabled())
                        .weeklyDigestEnabled(pref.getWeeklyDigestEnabled())
                        .resumeNudgeEnabled(pref.getResumeNudgeEnabled())
                        .savedJobChangeEnabled(pref.getSavedJobChangeEnabled())
                        .build())
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/candidate/notifications/unsubscribe?token=...
     * One-click unsubscribe from all emails (link in email footer).
     */
    @GetMapping("/unsubscribe")
    public Mono<ResponseEntity<String>> unsubscribe(@RequestParam String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body("Invalid token"));
        }
        return emailService.unsubscribeAll(token)
                .thenReturn(ResponseEntity.ok("You've been unsubscribed from all AIRRAL emails. You can re-enable notifications from your profile settings."));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
