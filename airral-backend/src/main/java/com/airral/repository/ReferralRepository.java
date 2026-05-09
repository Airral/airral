package com.airral.repository;

import com.airral.domain.Referral;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ReferralRepository extends R2dbcRepository<Referral, Long> {

    // Find all referrals for an organization
    @Query("SELECT * FROM referrals WHERE organization_id = :organizationId ORDER BY submitted_at DESC")
    Flux<Referral> findByOrganizationId(Long organizationId);

    // Find referrals by who referred them
    @Query("SELECT * FROM referrals WHERE referred_by_id = :userId ORDER BY submitted_at DESC")
    Flux<Referral> findByReferredById(Long userId);

    // Find referral by ID and organization
    @Query("SELECT * FROM referrals WHERE id = :id AND organization_id = :organizationId")
    Mono<Referral> findByIdAndOrganizationId(Long id, Long organizationId);

    // Find by status
    @Query("SELECT * FROM referrals WHERE organization_id = :organizationId AND status = :status ORDER BY submitted_at DESC")
    Flux<Referral> findByOrganizationIdAndStatus(Long organizationId, String status);

    // Find pending referrals (need review)
    @Query("SELECT * FROM referrals WHERE organization_id = :organizationId AND status = 'PENDING' ORDER BY submitted_at ASC")
    Flux<Referral> findPendingByOrganizationId(Long organizationId);

    // Count referrals by user
    @Query("SELECT COUNT(*) FROM referrals WHERE referred_by_id = :userId")
    Mono<Long> countByReferredById(Long userId);

    // Count accepted referrals by user (for bonus tracking)
    @Query("SELECT COUNT(*) FROM referrals WHERE referred_by_id = :userId AND status = 'HIRED'")
    Mono<Long> countHiredByReferredById(Long userId);
}
