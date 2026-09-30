package com.airral.repository;

import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface UserRepository extends R2dbcRepository<User, Long> {

    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByEmail(String email);

    /**
     * Finds the account already linked to a Google identity.
     *
     * <p>Tried before findByEmail on the Google sign-in path, and the order is
     * the point: an address is something two people can each claim -- one by
     * registering it here, one by owning it at Google -- while the sub can only
     * be asserted by Google. So the sub, when we hold one, decides which row a
     * credential is allowed to open.
     */
    Mono<User> findByGoogleSubject(String googleSubject);

    @Query("SELECT * FROM users WHERE email = :email AND organization_id = :organizationId")
    Mono<User> findByEmailAndOrganizationId(String email, Long organizationId);

    @Query("SELECT * FROM users WHERE organization_id = :organizationId")
    Flux<User> findByOrganizationId(Long organizationId);

    @Query("SELECT * FROM users WHERE organization_id = :organizationId AND role = :role")
    Flux<User> findByOrganizationIdAndRole(Long organizationId, UserRole role);

    @Query("SELECT * FROM users WHERE manager_id = :managerId")
    Flux<User> findByManagerId(Long managerId);

    @Query("SELECT COUNT(*) FROM users WHERE organization_id = :organizationId AND role = 'HR_MANAGER' AND is_active = true")
    Mono<Long> countActiveHrManagers(Long organizationId);

    @Query("SELECT * FROM users WHERE organization_id = :organizationId AND role = 'HR_MANAGER' AND is_active = true")
    Flux<User> findActiveHrManagers(Long organizationId);

    // The writes below change only their own columns. Saving a whole user row
    // read earlier can put back a role, an active flag or a token version that
    // someone changed in between: a sign-in that read the row before HR
    // switched the account off would switch it back on.

    @Modifying
    @Query("UPDATE users SET last_login_at = :at WHERE id = :id")
    Mono<Integer> touchLastLogin(Long id, LocalDateTime at);

    @Modifying
    @Query("UPDATE users SET role = :role, updated_at = :at WHERE id = :id AND organization_id = :organizationId")
    Mono<Integer> setRole(Long id, Long organizationId, String role, LocalDateTime at);

    @Modifying
    @Query("UPDATE users SET is_active = :active, updated_at = :at WHERE id = :id AND organization_id = :organizationId")
    Mono<Integer> setActive(Long id, Long organizationId, boolean active, LocalDateTime at);

    @Modifying
    @Query("UPDATE users SET first_name = :firstName, last_name = :lastName, phone = :phone, job_title = :jobTitle, " +
           "manager_id = :managerId, department_id = :departmentId, department = :department, updated_at = :at " +
           "WHERE id = :id")
    Mono<Integer> updateProfile(Long id, String firstName, String lastName, String phone, String jobTitle,
                                Long managerId, Long departmentId, String department, LocalDateTime at);


    /** Keeps the department name copied on users in step with a renamed department. */
    @Modifying
    @Query("UPDATE users SET department = :name WHERE department_id = :departmentId")
    Mono<Long> setDepartmentName(Long departmentId, String name);

    /** Takes a deleted department off users, name and all. */
    @Modifying
    @Query("UPDATE users SET department = NULL, department_id = NULL WHERE department_id = :departmentId")
    Mono<Long> clearDepartment(Long departmentId);
}
