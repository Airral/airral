package com.airral.repository;

import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends R2dbcRepository<User, Long> {

    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByEmail(String email);

    @Query("SELECT * FROM users WHERE email = :email AND organization_id = :organizationId")
    Mono<User> findByEmailAndOrganizationId(String email, Long organizationId);

    @Query("SELECT * FROM users WHERE organization_id = :organizationId")
    Flux<User> findByOrganizationId(Long organizationId);

    @Query("SELECT * FROM users WHERE organization_id = :organizationId AND role = :role")
    Flux<User> findByOrganizationIdAndRole(Long organizationId, UserRole role);

    @Query("SELECT * FROM users WHERE manager_id = :managerId")
    Flux<User> findByManagerId(Long managerId);

    @Query("SELECT * FROM users WHERE invitation_token = :token AND invitation_expires_at > CURRENT_TIMESTAMP")
    Mono<User> findByValidInvitationToken(String token);
}
