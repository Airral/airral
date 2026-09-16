package com.airral.controller;

import com.airral.dto.request.UpdateNotificationPreferencesRequest;
import com.airral.dto.response.NotificationPreferencesResponse;
import com.airral.dto.response.UnsubscribeResultResponse;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateEmailService;
import com.airral.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean schedulerEnabled;

    public CandidateNotificationController(
            CandidateEmailService emailService,
            JwtTokenProvider jwtTokenProvider,
            @Value("${airral.notifications.scheduler.enabled:false}") boolean schedulerEnabled) {
        this.emailService = emailService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * The same projection for both endpoints, so they cannot describe the row
     * differently. They were written out twice and had already drifted in what
     * they returned after a write.
     */
    private NotificationPreferencesResponse toResponse(
            com.airral.domain.CandidateNotificationPreference pref) {
        return NotificationPreferencesResponse.builder()
                .jobAlertEnabled(pref.getJobAlertEnabled())
                .followUpReminderEnabled(pref.getFollowUpReminderEnabled())
                .weeklyDigestEnabled(pref.getWeeklyDigestEnabled())
                .resumeNudgeEnabled(pref.getResumeNudgeEnabled())
                .savedJobChangeEnabled(pref.getSavedJobChangeEnabled())
                .emailDeliveryActive(schedulerEnabled)
                .build();
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
                .map(this::toResponse)
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
                    // The save that the line replaced here only claimed to have
                    // happened. This method fetched the row, applied the setters
                    // to that instance, and then called getOrCreatePreferences
                    // again -- and Spring Data R2DBC has no identity map, so the
                    // second call was a fresh SELECT returning a different
                    // instance hydrated from the untouched row. The mutated one
                    // was discarded unsaved, the response carried the OLD
                    // values, and the portal rebinds from the response, so the
                    // toggle visibly snapped back while "Preferences updated"
                    // was on screen. Every toggle on the page was inert.
                    return emailService.savePreferences(pref);
                })
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    /**
     * POST /api/candidate/notifications/unsubscribe
     *
     * <p>Switches every notification off for the holder of the token. Public,
     * because the person clicking a link in their mail client has no session
     * token for this API -- authorisation is the per-user UUID itself, and the
     * only thing the call can do is turn notifications off.
     *
     * <p>A POST, not a GET, and that is the whole point of the shape. Mail
     * clients and security scanners prefetch links, so a mutating GET gets
     * fired for people who never clicked and unsubscribes them silently. The
     * portal's /unsubscribe page is what the footer links to; it renders on a
     * plain GET, changes nothing, and posts here only when someone presses the
     * button.
     */
    @PostMapping("/unsubscribe")
    public Mono<ResponseEntity<UnsubscribeResultResponse>> unsubscribe(@RequestParam String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(new UnsubscribeResultResponse(
                    false, "That link is missing its code. You can turn notifications off in your profile.")));
        }
        return emailService.unsubscribeAll(token)
                .map(unsubscribed -> ResponseEntity.ok(unsubscribed
                        ? new UnsubscribeResultResponse(true,
                                "You have been unsubscribed from all AIRRAL emails.")
                        // Said plainly rather than as a success. Someone holding
                        // a stale link who is told they are unsubscribed carries
                        // on receiving mail and has no reason to look again.
                        : new UnsubscribeResultResponse(false,
                                "That unsubscribe link is no longer valid. Nothing has changed.")));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
