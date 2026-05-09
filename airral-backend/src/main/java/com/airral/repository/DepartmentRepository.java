package com.airral.repository;

import com.airral.domain.Department;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface DepartmentRepository extends R2dbcRepository<Department, Long> {

    // Find all departments for an organization
    @Query("SELECT * FROM departments WHERE organization_id = :organizationId ORDER BY name ASC")
    Flux<Department> findByOrganizationId(Long organizationId);

    // Find active departments
    @Query("SELECT * FROM departments WHERE organization_id = :organizationId AND is_active = true ORDER BY name ASC")
    Flux<Department> findActiveByOrganizationId(Long organizationId);

    // Find by ID and organization (security check)
    @Query("SELECT * FROM departments WHERE id = :id AND organization_id = :organizationId")
    Mono<Department> findByIdAndOrganizationId(Long id, Long organizationId);

    // Check if department name exists in organization
    @Query("SELECT COUNT(*) > 0 FROM departments WHERE organization_id = :organizationId AND LOWER(name) = LOWER(:name)")
    Mono<Boolean> existsByOrganizationIdAndName(Long organizationId, String name);
}
