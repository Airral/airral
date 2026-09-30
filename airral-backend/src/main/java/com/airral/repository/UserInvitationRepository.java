package com.airral.repository;

import com.airral.domain.UserInvitation;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface UserInvitationRepository extends R2dbcRepository<UserInvitation, Long> {

    // Find by invitation token
    @Query("SELECT * FROM user_invitations WHERE invitation_token = :token")
    Mono<UserInvitation> findByInvitationToken(String token);

    /**
     * The address's unaccepted invitation to the company, expired or not. The
     * unique index allows one per spelling of the address, and invitations older
     * than lower-casing may differ only in case, so the newest is the one.
     */
    @Query("SELECT * FROM user_invitations WHERE lower(email) = lower(:email) AND organization_id = :organizationId " +
           "AND accepted_at IS NULL ORDER BY created_at DESC LIMIT 1")
    Mono<UserInvitation> findUnacceptedByEmailAndOrganization(String email, Long organizationId);

    /** Every unaccepted invitation, expired ones included, so HR can resend or cancel them. */
    @Query("SELECT * FROM user_invitations WHERE organization_id = :organizationId AND accepted_at IS NULL " +
           "ORDER BY created_at DESC")
    Flux<UserInvitation> findUnacceptedByOrganizationId(Long organizationId);

    /** Invitations saved while the company waited for review, not emailed yet. */
    @Query("SELECT * FROM user_invitations WHERE organization_id = :organizationId AND accepted_at IS NULL " +
           "AND sent_at IS NULL ORDER BY created_at")
    Flux<UserInvitation> findHeldByOrganizationId(Long organizationId);

    /** Records that the invitation's email went out. */
    @Modifying
    @Query("UPDATE user_invitations SET sent_at = :at WHERE id = :id")
    Mono<Integer> markSent(Long id, LocalDateTime at);

    // Find all invitations for an organization
    @Query("SELECT * FROM user_invitations WHERE organization_id = :organizationId ORDER BY created_at DESC")
    Flux<UserInvitation> findByOrganizationId(Long organizationId);

    /** Keeps the department name copied on user_invitations in step with a renamed department. */
    @Modifying
    @Query("UPDATE user_invitations SET department = :name WHERE department_id = :departmentId")
    Mono<Long> setDepartmentName(Long departmentId, String name);

    /** Takes a deleted department off user_invitations, name and all. */
    @Modifying
    @Query("UPDATE user_invitations SET department = NULL, department_id = NULL WHERE department_id = :departmentId")
    Mono<Long> clearDepartment(Long departmentId);
}
