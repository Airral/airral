package com.airral.repository;

import com.airral.domain.Offer;
import com.airral.domain.enums.OfferStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OfferRepository extends R2dbcRepository<Offer, Long> {

    // Find offers by application
    @Query("SELECT * FROM offers WHERE application_id = :applicationId ORDER BY created_at DESC")
    Flux<Offer> findByApplicationId(Long applicationId);

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
