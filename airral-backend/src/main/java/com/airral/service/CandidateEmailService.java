package com.airral.service;

import com.airral.domain.CandidateNotificationPreference;
import com.airral.domain.User;
import com.airral.exception.NotFoundException;
import com.airral.repository.CandidateNotificationPreferenceRepository;
import com.airral.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Handles sending email notifications to candidates.
 * Uses Spring JavaMailSender (configured for Gmail SMTP or any SMTP provider).
 * All email sending is done on boundedElastic scheduler to avoid blocking the event loop.
 */
@Service
public class CandidateEmailService {

    private static final Logger log = LoggerFactory.getLogger(CandidateEmailService.class);

    private final JavaMailSender mailSender;
    private final CandidateNotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final String fromAddress;
    private final String fromName;
    private final String appBaseUrl;
    private final boolean emailEnabled;

    public CandidateEmailService(
            JavaMailSender mailSender,
            CandidateNotificationPreferenceRepository preferenceRepository,
            UserRepository userRepository,
            @Value("${airral.notifications.email.from-address:notifications@airral.com}") String fromAddress,
            @Value("${airral.notifications.email.from-name:AIRRAL}") String fromName,
            @Value("${airral.notifications.email.app-base-url:https://apply.airral.com}") String appBaseUrl,
            @Value("${airral.notifications.email.enabled:true}") boolean emailEnabled) {
        this.mailSender = mailSender;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.appBaseUrl = appBaseUrl;
        this.emailEnabled = emailEnabled;
    }

    /**
     * Send an HTML email to a user. Handles errors gracefully — logs and continues.
     */
    public Mono<Void> sendEmail(String toEmail, String subject, String htmlBody) {
        if (!emailEnabled) {
            log.debug("Email sending disabled. Would have sent '{}' to {}", subject, toEmail);
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromAddress, fromName);
                helper.setTo(toEmail);
                helper.setSubject(subject);
                helper.setText(htmlBody, true);
                mailSender.send(message);
                log.info("Sent email '{}' to {}", subject, toEmail);
            } catch (MessagingException | java.io.UnsupportedEncodingException e) {
                log.error("Failed to send email '{}' to {}: {}", subject, toEmail, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Get or create notification preferences for a user (opt-in by default).
     */
    public Mono<CandidateNotificationPreference> getOrCreatePreferences(Long userId) {
        return preferenceRepository.findByUserId(userId)
                .switchIfEmpty(createDefaultPreferences(userId));
    }

    /**
     * Get or create notification preferences by email.
     */
    public Mono<CandidateNotificationPreference> getOrCreatePreferences(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> getOrCreatePreferences(user.getId()));
    }

    /**
     * Unsubscribe via token (one-click unsubscribe from email footer).
     */
    public Mono<Void> unsubscribeAll(String unsubscribeToken) {
        return preferenceRepository.findByUnsubscribeToken(unsubscribeToken)
                .flatMap(pref -> {
                    pref.setJobAlertEnabled(false);
                    pref.setFollowUpReminderEnabled(false);
                    pref.setWeeklyDigestEnabled(false);
                    pref.setResumeNudgeEnabled(false);
                    pref.setSavedJobChangeEnabled(false);
                    pref.setUpdatedAt(OffsetDateTime.now());
                    return preferenceRepository.save(pref);
                })
                .then();
    }

    /**
     * Build the unsubscribe URL for email footers.
     */
    public String unsubscribeUrl(String unsubscribeToken) {
        return appBaseUrl + "/unsubscribe?token=" + unsubscribeToken;
    }

    /**
     * Build a standard email footer with unsubscribe link.
     */
    public String emailFooter(String unsubscribeToken) {
        return """
                <div style="margin-top:32px; padding-top:16px; border-top:1px solid #e1e5e9; font-size:12px; color:#667789;">
                  <p>You're receiving this because you have an AIRRAL account.</p>
                  <p><a href="%s" style="color:#667789;">Unsubscribe from all emails</a> · <a href="%s/settings" style="color:#667789;">Manage preferences</a></p>
                  <p style="margin-top:8px;">AIRRAL · Job search, simplified.</p>
                </div>
                """.formatted(unsubscribeUrl(unsubscribeToken), appBaseUrl);
    }

    /**
     * Wrap content in the standard AIRRAL email template.
     */
    public String wrapInTemplate(String subject, String bodyHtml, String unsubscribeToken) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>%s</title>
                </head>
                <body style="margin:0; padding:0; background-color:#f6f7f6; font-family:-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                  <div style="max-width:600px; margin:0 auto; padding:32px 16px;">
                    <div style="margin-bottom:24px;">
                      <span style="font-size:20px; font-weight:700; color:#007C6D;">AIRRAL</span>
                    </div>
                    <div style="background:#ffffff; border-radius:8px; padding:32px; border:1px solid #e1e5e9;">
                      %s
                    </div>
                    %s
                  </div>
                </body>
                </html>
                """.formatted(subject, bodyHtml, emailFooter(unsubscribeToken));
    }

    private Mono<CandidateNotificationPreference> createDefaultPreferences(Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        CandidateNotificationPreference pref = CandidateNotificationPreference.builder()
                .userId(userId)
                .jobAlertEnabled(true)
                .followUpReminderEnabled(true)
                .weeklyDigestEnabled(true)
                .resumeNudgeEnabled(true)
                .savedJobChangeEnabled(true)
                .unsubscribeToken(UUID.randomUUID().toString())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return preferenceRepository.save(pref);
    }
}
