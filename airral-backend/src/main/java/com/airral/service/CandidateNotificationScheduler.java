package com.airral.service;

import com.airral.domain.CandidateNotificationPreference;
import com.airral.domain.CandidateSavedJob;
import com.airral.domain.User;
import com.airral.repository.CandidateNotificationPreferenceRepository;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.CandidateSavedJobRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Scheduled notification service that sends:
 * 1. New job match alerts (daily at 9am)
 * 2. Follow-up reminders (daily at 10am)
 * 3. Weekly digest (Sundays at 7pm)
 * 4. Resume nudge (2 days after upload with no fit run)
 *
 * Each scheduler queries eligible users and sends emails via CandidateEmailService.
 * Runs only when airral.notifications.scheduler.enabled=true.
 */
@Service
@ConditionalOnProperty(prefix = "airral.notifications.scheduler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CandidateNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CandidateNotificationScheduler.class);

    private final CandidateNotificationPreferenceRepository preferenceRepository;
    private final CandidateSavedJobRepository savedJobRepository;
    private final CandidateProfileRepository profileRepository;
    private final CandidateResumeDocumentRepository resumeDocumentRepository;
    private final UserRepository userRepository;
    private final CandidateEmailService emailService;
    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;
    private final String appBaseUrl;

    public CandidateNotificationScheduler(
            CandidateNotificationPreferenceRepository preferenceRepository,
            CandidateSavedJobRepository savedJobRepository,
            CandidateProfileRepository profileRepository,
            CandidateResumeDocumentRepository resumeDocumentRepository,
            UserRepository userRepository,
            CandidateEmailService emailService,
            DatabaseClient databaseClient,
            ObjectMapper objectMapper,
            @Value("${airral.notifications.email.app-base-url:https://apply.airral.com}") String appBaseUrl) {
        this.preferenceRepository = preferenceRepository;
        this.savedJobRepository = savedJobRepository;
        this.profileRepository = profileRepository;
        this.resumeDocumentRepository = resumeDocumentRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
        this.appBaseUrl = appBaseUrl;
    }

    // ==================== 1. NEW JOB MATCH ALERTS (Daily 9:00 AM) ====================

    @Scheduled(cron = "${airral.notifications.job-alert.cron:0 0 9 * * *}")
    public void sendJobAlerts() {
        log.info("Starting daily job match alert notifications");
        preferenceRepository.findAllWithJobAlertsEnabled()
                .filter(pref -> pref.getLastJobAlertSentAt() == null
                        || pref.getLastJobAlertSentAt().isBefore(OffsetDateTime.now().minus(20, ChronoUnit.HOURS)))
                .flatMap(this::sendJobAlertForUser, 4)
                .count()
                .doOnSuccess(count -> log.info("Sent {} job alert emails", count))
                .doOnError(error -> log.error("Job alert scheduler failed: {}", error.getMessage()))
                .subscribe();
    }

    private Mono<Void> sendJobAlertForUser(CandidateNotificationPreference pref) {
        return userRepository.findById(pref.getUserId())
                .flatMap(user -> countNewJobsSince(pref.getLastJobAlertSentAt())
                        .filter(count -> count > 0)
                        .flatMap(newJobCount -> {
                            String subject = newJobCount + " new roles match your profile";
                            String body = buildJobAlertBody(user, newJobCount, pref.getUnsubscribeToken());
                            return emailService.sendEmail(user.getEmail(), subject, body)
                                    .then(updateLastSent(pref, "jobAlert"));
                        }))
                .onErrorResume(error -> {
                    log.warn("Failed to send job alert to userId={}: {}", pref.getUserId(), error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Long> countNewJobsSince(OffsetDateTime since) {
        OffsetDateTime cutoff = since != null ? since : OffsetDateTime.now().minus(1, ChronoUnit.DAYS);
        return databaseClient.sql("""
                        SELECT COUNT(*) as cnt
                        FROM external_job_postings
                        WHERE created_at > :cutoff
                          AND expired_at IS NULL
                        """)
                .bind("cutoff", cutoff)
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private String buildJobAlertBody(User user, long newJobCount, String unsubscribeToken) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "there";
        String bodyHtml = """
                <h2 style="color:#111827; margin:0 0 16px;">Hey %s, %d new roles just landed</h2>
                <p style="color:#4b5563; line-height:1.6;">
                  We found <strong>%d new jobs</strong> since your last visit that may match your profile.
                  Check them out before they get buried.
                </p>
                <div style="margin:24px 0;">
                  <a href="%s/jobs" style="display:inline-block; padding:12px 24px; background-color:#007C6D; color:#ffffff; text-decoration:none; border-radius:6px; font-weight:600;">
                    View New Roles
                  </a>
                </div>
                <p style="color:#667789; font-size:14px;">
                  Tip: Run resume fit on your top picks to see where you stand before applying.
                </p>
                """.formatted(firstName, newJobCount, newJobCount, appBaseUrl);
        return emailService.wrapInTemplate("New job matches", bodyHtml, unsubscribeToken);
    }

    // ==================== 2. FOLLOW-UP REMINDERS (Daily 10:00 AM) ====================

    @Scheduled(cron = "${airral.notifications.follow-up.cron:0 0 10 * * *}")
    public void sendFollowUpReminders() {
        log.info("Starting daily follow-up reminder notifications");
        preferenceRepository.findAllWithFollowUpRemindersEnabled()
                .filter(pref -> pref.getLastFollowUpReminderSentAt() == null
                        || pref.getLastFollowUpReminderSentAt().isBefore(OffsetDateTime.now().minus(20, ChronoUnit.HOURS)))
                .flatMap(this::sendFollowUpForUser, 4)
                .count()
                .doOnSuccess(count -> log.info("Sent {} follow-up reminder emails", count))
                .doOnError(error -> log.error("Follow-up reminder scheduler failed: {}", error.getMessage()))
                .subscribe();
    }

    private Mono<Void> sendFollowUpForUser(CandidateNotificationPreference pref) {
        return userRepository.findById(pref.getUserId())
                .flatMap(user -> findOverdueFollowUps(user.getId())
                        .collectList()
                        .filter(jobs -> !jobs.isEmpty())
                        .flatMap(overdueJobs -> {
                            String subject = overdueJobs.size() == 1
                                    ? "Follow up on your application"
                                    : overdueJobs.size() + " applications need follow-up";
                            String body = buildFollowUpBody(user, overdueJobs, pref.getUnsubscribeToken());
                            return emailService.sendEmail(user.getEmail(), subject, body)
                                    .then(updateLastSent(pref, "followUp"));
                        }))
                .onErrorResume(error -> {
                    log.warn("Failed to send follow-up to userId={}: {}", pref.getUserId(), error.getMessage());
                    return Mono.empty();
                });
    }

    private Flux<CandidateSavedJob> findOverdueFollowUps(Long userId) {
        return savedJobRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .filter(job -> job.getNextStepDueAt() != null
                        && job.getNextStepDueAt().isBefore(OffsetDateTime.now())
                        && !"REJECTED".equals(job.getStatus())
                        && !"ARCHIVED".equals(job.getStatus()));
    }

    private String buildFollowUpBody(User user, List<CandidateSavedJob> overdueJobs, String unsubscribeToken) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "there";
        StringBuilder jobList = new StringBuilder();
        for (CandidateSavedJob job : overdueJobs.stream().limit(5).toList()) {
            String jobKey = job.getSourceJobKey() != null ? job.getSourceJobKey() : "Unknown";
            String nextStep = job.getNextStep() != null ? job.getNextStep() : "Follow up";
            jobList.append("""
                    <div style="padding:12px 0; border-bottom:1px solid #e1e5e9;">
                      <div style="font-weight:600; color:#111827;">%s</div>
                      <div style="color:#667789; font-size:14px;">Next: %s</div>
                    </div>
                    """.formatted(formatJobKey(jobKey), nextStep));
        }

        String bodyHtml = """
                <h2 style="color:#111827; margin:0 0 16px;">Hey %s, time to follow up</h2>
                <p style="color:#4b5563; line-height:1.6;">
                  You have <strong>%d application(s)</strong> that need attention:
                </p>
                <div style="margin:16px 0;">
                  %s
                </div>
                <div style="margin:24px 0;">
                  <a href="%s/tracker" style="display:inline-block; padding:12px 24px; background-color:#007C6D; color:#ffffff; text-decoration:none; border-radius:6px; font-weight:600;">
                    Open Tracker
                  </a>
                </div>
                <p style="color:#667789; font-size:14px;">
                  Following up within 7 days shows interest without being pushy.
                </p>
                """.formatted(firstName, overdueJobs.size(), jobList, appBaseUrl);
        return emailService.wrapInTemplate("Follow-up reminder", bodyHtml, unsubscribeToken);
    }

    // ==================== 3. WEEKLY DIGEST (Sundays 7:00 PM) ====================

    @Scheduled(cron = "${airral.notifications.weekly-digest.cron:0 0 19 * * SUN}")
    public void sendWeeklyDigest() {
        log.info("Starting weekly digest notifications");
        preferenceRepository.findAllWithWeeklyDigestEnabled()
                .filter(pref -> pref.getLastWeeklyDigestSentAt() == null
                        || pref.getLastWeeklyDigestSentAt().isBefore(OffsetDateTime.now().minus(6, ChronoUnit.DAYS)))
                .flatMap(this::sendDigestForUser, 4)
                .count()
                .doOnSuccess(count -> log.info("Sent {} weekly digest emails", count))
                .doOnError(error -> log.error("Weekly digest scheduler failed: {}", error.getMessage()))
                .subscribe();
    }

    private Mono<Void> sendDigestForUser(CandidateNotificationPreference pref) {
        return userRepository.findById(pref.getUserId())
                .flatMap(user -> {
                    OffsetDateTime weekAgo = OffsetDateTime.now().minus(7, ChronoUnit.DAYS);
                    Mono<Long> newJobsMono = countNewJobsSince(weekAgo);
                    Mono<Long> savedCountMono = countSavedJobs(user.getId());
                    Mono<Long> overdueCountMono = findOverdueFollowUps(user.getId()).count();

                    return Mono.zip(newJobsMono, savedCountMono, overdueCountMono)
                            .filter(tuple -> tuple.getT1() > 0 || tuple.getT3() > 0)
                            .flatMap(tuple -> {
                                long newJobs = tuple.getT1();
                                long savedJobs = tuple.getT2();
                                long overdue = tuple.getT3();
                                String subject = "Your weekly job search summary";
                                String body = buildDigestBody(user, newJobs, savedJobs, overdue, pref.getUnsubscribeToken());
                                return emailService.sendEmail(user.getEmail(), subject, body)
                                        .then(updateLastSent(pref, "weeklyDigest"));
                            });
                })
                .onErrorResume(error -> {
                    log.warn("Failed to send digest to userId={}: {}", pref.getUserId(), error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Long> countSavedJobs(Long userId) {
        return savedJobRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .filter(job -> !"ARCHIVED".equals(job.getStatus()) && !"REJECTED".equals(job.getStatus()))
                .count();
    }

    private String buildDigestBody(User user, long newJobs, long savedJobs, long overdue, String unsubscribeToken) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "there";

        String overdueSection = overdue > 0 ? """
                <div style="padding:12px 16px; background:#FFF7ED; border-radius:6px; margin:12px 0;">
                  <span style="color:#b87911; font-weight:600;">⏰ %d application(s) need follow-up</span>
                </div>
                """.formatted(overdue) : "";

        String bodyHtml = """
                <h2 style="color:#111827; margin:0 0 16px;">Your week in review, %s</h2>
                <div style="margin:16px 0;">
                  <div style="display:flex; gap:16px; flex-wrap:wrap;">
                    <div style="padding:16px; background:#E7F5F1; border-radius:8px; flex:1; min-width:120px; text-align:center;">
                      <div style="font-size:24px; font-weight:700; color:#007C6D;">%d</div>
                      <div style="font-size:12px; color:#4b5563;">New roles</div>
                    </div>
                    <div style="padding:16px; background:#f6f7f6; border-radius:8px; flex:1; min-width:120px; text-align:center;">
                      <div style="font-size:24px; font-weight:700; color:#111827;">%d</div>
                      <div style="font-size:12px; color:#4b5563;">Saved jobs</div>
                    </div>
                  </div>
                </div>
                %s
                <div style="margin:24px 0;">
                  <a href="%s/jobs" style="display:inline-block; padding:12px 24px; background-color:#007C6D; color:#ffffff; text-decoration:none; border-radius:6px; font-weight:600;">
                    Continue Job Search
                  </a>
                </div>
                <p style="color:#667789; font-size:14px;">
                  Consistency wins. Even reviewing 2-3 roles a week keeps your search moving forward.
                </p>
                """.formatted(firstName, newJobs, savedJobs, overdueSection, appBaseUrl);
        return emailService.wrapInTemplate("Weekly digest", bodyHtml, unsubscribeToken);
    }

    // ==================== 4. RESUME NUDGE (Daily 11:00 AM) ====================

    @Scheduled(cron = "${airral.notifications.resume-nudge.cron:0 0 11 * * *}")
    public void sendResumeNudges() {
        log.info("Starting resume nudge notifications");
        preferenceRepository.findAllWithResumeNudgeEnabled()
                .filter(pref -> pref.getLastResumeNudgeSentAt() == null)
                .flatMap(this::sendResumeNudgeForUser, 4)
                .count()
                .doOnSuccess(count -> log.info("Sent {} resume nudge emails", count))
                .doOnError(error -> log.error("Resume nudge scheduler failed: {}", error.getMessage()))
                .subscribe();
    }

    private Mono<Void> sendResumeNudgeForUser(CandidateNotificationPreference pref) {
        return userRepository.findById(pref.getUserId())
                .flatMap(user -> profileRepository.findByUserId(user.getId())
                        .filter(profile -> profile.getActiveResumeDocumentId() != null)
                        .flatMap(profile -> resumeDocumentRepository.findByIdAndUserId(
                                profile.getActiveResumeDocumentId(), user.getId()))
                        .filter(doc -> doc.getCreatedAt() != null
                                && doc.getCreatedAt().isBefore(java.time.LocalDateTime.now().minus(2, ChronoUnit.DAYS)))
                        .flatMap(doc -> hasNoFitResults(user.getId())
                                .filter(noFit -> noFit)
                                .flatMap(ignored -> {
                                    String subject = "Your resume is uploaded — check how it matches roles";
                                    String body = buildResumeNudgeBody(user, pref.getUnsubscribeToken());
                                    return emailService.sendEmail(user.getEmail(), subject, body)
                                            .then(updateLastSent(pref, "resumeNudge"));
                                })))
                .onErrorResume(error -> {
                    log.warn("Failed to send resume nudge to userId={}: {}", pref.getUserId(), error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Boolean> hasNoFitResults(Long userId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) as cnt
                        FROM candidate_job_fit_results
                        WHERE user_id = :userId
                        """)
                .bind("userId", userId)
                .map((row, meta) -> row.get("cnt", Long.class))
                .one()
                .map(count -> count == 0)
                .defaultIfEmpty(true);
    }

    private String buildResumeNudgeBody(User user, String unsubscribeToken) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "there";
        String bodyHtml = """
                <h2 style="color:#111827; margin:0 0 16px;">Hey %s, your resume is ready</h2>
                <p style="color:#4b5563; line-height:1.6;">
                  You uploaded your resume but haven't checked how it matches any specific role yet.
                  Pick a job you like and run <strong>Resume Fit</strong> — it takes 5 seconds and shows
                  exactly what's missing.
                </p>
                <div style="margin:24px 0;">
                  <a href="%s/jobs" style="display:inline-block; padding:12px 24px; background-color:#007C6D; color:#ffffff; text-decoration:none; border-radius:6px; font-weight:600;">
                    Find a Role &amp; Check Fit
                  </a>
                </div>
                <p style="color:#667789; font-size:14px;">
                  Tailoring your resume for a specific job increases interview callbacks by 3-5x.
                </p>
                """.formatted(firstName, appBaseUrl);
        return emailService.wrapInTemplate("Resume fit check", bodyHtml, unsubscribeToken);
    }

    // ==================== Helpers ====================

    private Mono<Void> updateLastSent(CandidateNotificationPreference pref, String type) {
        OffsetDateTime now = OffsetDateTime.now();
        switch (type) {
            case "jobAlert" -> pref.setLastJobAlertSentAt(now);
            case "followUp" -> pref.setLastFollowUpReminderSentAt(now);
            case "weeklyDigest" -> pref.setLastWeeklyDigestSentAt(now);
            case "resumeNudge" -> pref.setLastResumeNudgeSentAt(now);
        }
        pref.setUpdatedAt(now);
        return preferenceRepository.save(pref).then();
    }

    private String formatJobKey(String sourceJobKey) {
        // Format "greenhouse:airbnb:12345" → "Airbnb"
        String[] parts = sourceJobKey.split(":", 3);
        if (parts.length >= 2) {
            String company = parts[1];
            return company.substring(0, 1).toUpperCase() + company.substring(1);
        }
        return sourceJobKey;
    }
}
