package com.airral.repository;

import com.airral.domain.UserInvitation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserInvitationRepository extends R2dbcRepository<UserInvitation, Long> {

    // Find by invitation token
    @Query("SELECT * FROM user_invitations WHERE invitation_token = :token")
    Mono<UserInvitation> findByInvitationToken(String token);

    // Find valid (not expired, not accepted) invitation by email and org
    @Query("SELECT * FROM user_invitations " +
           "WHERE email = :email AND organization_id = :organizationId " +
           "AND accepted_at IS NULL AND expires_at > CURRENT_TIMESTAMP")
    Mono<UserInvitation> findValidInvitationByEmailAndOrganization(String email, Long organizationId);

    // Find all invitations for an organization
    @Query("SELECT * FROM user_invitations WHERE organization_id = :organizationId ORDER BY created_at DESC")
    Flux<UserInvitation> findByOrganizationId(Long organizationId);

    // Find pending invitations
    @Query("SELECT * FROM user_invitations " +
           "WHERE organization_id = :organizationId " +
           "AND accepted_at IS NULL AND expires_at > CURRENT_TIMESTAMP " +
           "ORDER BY created_at DESC")
    Flux<UserInvitation> findPendingByOrganizationId(Long organizationId);
}
