package com.airral.repository;

import com.airral.domain.CandidateNotificationPreference;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface CandidateNotificationPreferenceRepository extends R2dbcRepository<CandidateNotificationPreference, Long> {

    Mono<CandidateNotificationPreference> findByUserId(Long userId);

    Mono<CandidateNotificationPreference> findByUnsubscribeToken(String unsubscribeToken);

    @Query("SELECT * FROM candidate_notification_preferences WHERE job_alert_enabled = true")
    Flux<CandidateNotificationPreference> findAllWithJobAlertsEnabled();

    @Query("SELECT * FROM candidate_notification_preferences WHERE follow_up_reminder_enabled = true")
    Flux<CandidateNotificationPreference> findAllWithFollowUpRemindersEnabled();

    @Query("SELECT * FROM candidate_notification_preferences WHERE weekly_digest_enabled = true")
    Flux<CandidateNotificationPreference> findAllWithWeeklyDigestEnabled();

    @Query("SELECT * FROM candidate_notification_preferences WHERE resume_nudge_enabled = true")
    Flux<CandidateNotificationPreference> findAllWithResumeNudgeEnabled();
}
