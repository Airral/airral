package com.airral.repository;

import com.airral.domain.Offer;
import com.airral.domain.enums.OfferStatus;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface OfferRepository extends R2dbcRepository<Offer, Long> {

    // Find offers by application
    @Query("SELECT * FROM offers WHERE application_id = :applicationId ORDER BY created_at DESC")
    Flux<Offer> findByApplicationId(Long applicationId);

    /**
     * Whether the application has an offer still being made: a draft, or sent
     * and neither answered nor past its date. One that lapsed does not stand in
     * the way of a new one.
     */
    @Query("SELECT COUNT(*) > 0 FROM offers WHERE application_id = :applicationId " +
           "AND (status = 'DRAFT' OR (status = 'SENT' AND (expires_at IS NULL OR expires_at >= :now)))")
    Mono<Boolean> existsOpenByApplicationId(Long applicationId, LocalDateTime now);

    /** Whether a sent offer is waiting for the candidate's answer. */
    @Query("SELECT COUNT(*) > 0 FROM offers WHERE application_id = :applicationId " +
           "AND status = 'SENT' AND (expires_at IS NULL OR expires_at >= :now)")
    Mono<Boolean> existsAwaitingAnswer(Long applicationId, LocalDateTime now);

    /**
     * Records sent offers past their date as expired. An expired offer reads as
     * one anyway; this frees the application's one open offer for a new one.
     */
    @Modifying
    @Query("UPDATE offers SET status = 'EXPIRED', updated_at = :now, version = version + 1 " +
           "WHERE application_id = :applicationId AND status = 'SENT' AND expires_at < :now")
    Mono<Long> expireLapsed(Long applicationId, LocalDateTime now);

    /** Closes the application's drafts and sent offers: withdrawn, or expired when past their date. */
    @Modifying
    @Query("UPDATE offers SET status = CASE WHEN status = 'SENT' AND expires_at < :now THEN 'EXPIRED' ELSE 'WITHDRAWN' END, " +
           "updated_at = :now, version = version + 1 " +
           "WHERE application_id = :applicationId AND status IN ('DRAFT', 'SENT')")
    Mono<Long> closeOpen(Long applicationId, LocalDateTime now);

    /** Closes the application's sent offers, leaving drafts alone. */
    @Modifying
    @Query("UPDATE offers SET status = CASE WHEN expires_at < :now THEN 'EXPIRED' ELSE 'WITHDRAWN' END, " +
           "updated_at = :now, version = version + 1 " +
           "WHERE application_id = :applicationId AND status = 'SENT'")
    Mono<Long> closeSent(Long applicationId, LocalDateTime now);

    /** The job's applications with an offer still being made. */
    @Query("SELECT DISTINCT o.application_id FROM offers o JOIN applications a ON o.application_id = a.id " +
           "WHERE a.job_id = :jobId " +
           "AND (o.status = 'DRAFT' OR (o.status = 'SENT' AND (o.expires_at IS NULL OR o.expires_at >= :now)))")
    Flux<Long> findApplicationIdsWithOpenOffers(Long jobId, LocalDateTime now);

    /**
     * An applicant's own offers, newest first, once sent. A draft is the
     * company's until then, and one withdrawn before it was sent never reached them.
     */
    @Query("SELECT o.* FROM offers o JOIN applications a ON o.application_id = a.id " +
           "WHERE a.applicant_id = :applicantId AND o.sent_at IS NOT NULL ORDER BY o.created_at DESC")
    Flux<Offer> findSentByApplicantId(Long applicantId);

    // Find all offers for an organization (via application -> job join)
    @Query("SELECT o.* FROM offers o " +
           "JOIN applications a ON o.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId " +
           "ORDER BY o.created_at DESC")
    Flux<Offer> findAllByOrganizationId(Long organizationId);

    // Find offer by ID with organization check
    @Query("SELECT o.* FROM offers o " +
           "JOIN applications a ON o.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE o.id = :id AND j.organization_id = :organizationId")
    Mono<Offer> findByIdAndOrganizationId(Long id, Long organizationId);

    // Find offers by status for an organization
    @Query("SELECT o.* FROM offers o " +
           "JOIN applications a ON o.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId AND o.status = :status " +
           "ORDER BY o.created_at DESC")
    Flux<Offer> findByOrganizationIdAndStatus(Long organizationId, OfferStatus status);

    // Count offers by organization
    @Query("SELECT COUNT(*) FROM offers o " +
           "JOIN applications a ON o.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId")
    Mono<Long> countByOrganizationId(Long organizationId);

    // Count accepted offers
    @Query("SELECT COUNT(*) FROM offers o " +
           "JOIN applications a ON o.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId AND o.status = 'ACCEPTED'")
    Mono<Long> countAcceptedByOrganizationId(Long organizationId);
}
