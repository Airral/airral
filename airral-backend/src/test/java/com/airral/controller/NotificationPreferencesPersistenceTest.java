package com.airral.controller;

import com.airral.domain.CandidateNotificationPreference;
import com.airral.dto.request.UpdateNotificationPreferencesRequest;
import com.airral.dto.response.NotificationPreferencesResponse;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification toggles have to reach the database.
 *
 * <p>They did not. {@code updatePreferences} fetched the row, applied the
 * setters to that instance, and then called {@code getOrCreatePreferences}
 * again. Spring Data R2DBC keeps no identity map, so the second call was a
 * fresh SELECT that returned a different instance hydrated from the untouched
 * row; the mutated one was discarded unsaved. The response therefore carried
 * the OLD values, and because the portal rebinds its state from the response,
 * the toggle visibly snapped back while "Preferences updated" was on screen.
 *
 * <p>Both halves are asserted, because either alone passes while the feature is
 * broken: that a save actually happens, and that the response describes what was
 * written rather than what was already there.
 */
class NotificationPreferencesPersistenceTest {

    private final CandidateEmailService emailService = mock(CandidateEmailService.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    /** Every account in these tests has proven its address; the gate is covered in EmailVerificationGateTest. */
    private final com.airral.service.AccountVerificationService verified = verifiedAccounts();

    private static com.airral.service.AccountVerificationService verifiedAccounts() {
        com.airral.service.AccountVerificationService service = mock(com.airral.service.AccountVerificationService.class);
        org.mockito.Mockito.when(service.requireVerified(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        return service;
    }
    /** Delivery off, which is how the deployed service is configured today. */
    private final CandidateNotificationController controller =
            new CandidateNotificationController(emailService, jwtTokenProvider, verified, false);

    private CandidateNotificationPreference allOn() {
        return CandidateNotificationPreference.builder()
                .id(42L)
                .userId(7L)
                .jobAlertEnabled(true)
                .followUpReminderEnabled(true)
                .weeklyDigestEnabled(true)
                .resumeNudgeEnabled(true)
                .savedJobChangeEnabled(true)
                .updatedAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    @Test
    @DisplayName("turning a toggle off writes it, and the response says it is off")
    void turningAToggleOffPersists() {
        CandidateNotificationPreference stored = allOn();
        when(jwtTokenProvider.getEmailFromToken("tok")).thenReturn("candidate@example.com");
        when(emailService.getOrCreatePreferences("candidate@example.com")).thenReturn(Mono.just(stored));
        // The repository echoes back whatever it was handed, as a real save does.
        when(emailService.savePreferences(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setWeeklyDigestEnabled(false);

        ResponseEntity<NotificationPreferencesResponse> response =
                controller.updatePreferences(request, "Bearer tok").block();

        ArgumentCaptor<CandidateNotificationPreference> saved =
                ArgumentCaptor.forClass(CandidateNotificationPreference.class);
        verify(emailService).savePreferences(saved.capture());

        assertThat(saved.getValue().getWeeklyDigestEnabled())
                .as("the value the user chose has to be the value handed to the repository")
                .isFalse();
        assertThat(saved.getValue().getId())
                .as("an entity with no id would be inserted, and user_id is unique")
                .isEqualTo(42L);

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getWeeklyDigestEnabled())
                .as("the portal rebinds from this response; stale values make the toggle snap back")
                .isFalse();
    }

    @Test
    @DisplayName("an absent field is left alone rather than reset")
    void absentFieldsAreUntouched() {
        CandidateNotificationPreference stored = allOn();
        stored.setResumeNudgeEnabled(false);
        when(jwtTokenProvider.getEmailFromToken("tok")).thenReturn("candidate@example.com");
        when(emailService.getOrCreatePreferences("candidate@example.com")).thenReturn(Mono.just(stored));
        when(emailService.savePreferences(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setJobAlertEnabled(false);

        ResponseEntity<NotificationPreferencesResponse> response =
                controller.updatePreferences(request, "Bearer tok").block();

        assertThat(response).isNotNull();
        assertThat(response.getBody().getJobAlertEnabled()).isFalse();
        assertThat(response.getBody().getResumeNudgeEnabled())
                .as("a partial update must not revive a setting the user had switched off")
                .isFalse();
        assertThat(response.getBody().getWeeklyDigestEnabled())
                .as("nor switch off one they had left on")
                .isTrue();
    }

    @Test
    @DisplayName("updatedAt is stamped so the write is visible in the row")
    void updatedAtIsStamped() {
        CandidateNotificationPreference stored = allOn();
        OffsetDateTime before = stored.getUpdatedAt();
        when(jwtTokenProvider.getEmailFromToken("tok")).thenReturn("candidate@example.com");
        when(emailService.getOrCreatePreferences("candidate@example.com")).thenReturn(Mono.just(stored));
        when(emailService.savePreferences(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setSavedJobChangeEnabled(false);
        controller.updatePreferences(request, "Bearer tok").block();

        ArgumentCaptor<CandidateNotificationPreference> saved =
                ArgumentCaptor.forClass(CandidateNotificationPreference.class);
        verify(emailService).savePreferences(saved.capture());
        assertThat(saved.getValue().getUpdatedAt()).isAfter(before);
    }

    /**
     * The page has to be able to say whether any of this is switched on.
     *
     * <p>Five toggles were offered over a delivery path that does not run: the
     * scheduler is gated on {@code airral.notifications.scheduler.enabled},
     * which defaults to false and is not set on the deployed service. The flag
     * is carried in the response so the notice the portal draws from it clears
     * itself when the property is turned on, instead of going stale the other
     * way.
     */
    @Test
    @DisplayName("the response says whether anything is actually sending these emails")
    void responseReportsWhetherDeliveryIsActive() {
        when(jwtTokenProvider.getEmailFromToken("tok")).thenReturn("candidate@example.com");
        when(emailService.getOrCreatePreferences("candidate@example.com")).thenReturn(Mono.just(allOn()));
        when(emailService.savePreferences(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        assertThat(controller.getPreferences("Bearer tok").block().getBody().getEmailDeliveryActive())
                .as("the scheduler is disabled, so nothing is being sent")
                .isFalse();

        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setWeeklyDigestEnabled(false);
        assertThat(controller.updatePreferences(request, "Bearer tok").block().getBody().getEmailDeliveryActive())
                .as("both endpoints describe the row the same way")
                .isFalse();

        CandidateNotificationController withDelivery =
                new CandidateNotificationController(emailService, jwtTokenProvider, verified, true);
        assertThat(withDelivery.getPreferences("Bearer tok").block().getBody().getEmailDeliveryActive())
                .as("and it follows the property rather than being hardcoded")
                .isTrue();
    }

    @Test
    @DisplayName("reading preferences does not write")
    void getDoesNotWrite() {
        when(jwtTokenProvider.getEmailFromToken("tok")).thenReturn("candidate@example.com");
        when(emailService.getOrCreatePreferences("candidate@example.com")).thenReturn(Mono.just(allOn()));

        controller.getPreferences("Bearer tok").block();

        verify(emailService, never()).savePreferences(any());
    }
}
