package com.airral.service;

import com.airral.domain.Department;
import com.airral.dto.request.CreateDepartmentRequest;
import com.airral.dto.response.DepartmentResponse;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.DepartmentRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    public DepartmentService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    /**
     * Create a new department
     */
    public Mono<DepartmentResponse> createDepartment(CreateDepartmentRequest request, Long organizationId) {
        // Check if department name already exists
        return departmentRepository.existsByOrganizationIdAndName(organizationId, request.getName())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ConflictException("Department with this name already exists"));
                    }

                    Department department = Department.builder()
                            .organizationId(organizationId)
                            .name(request.getName())
                            .description(request.getDescription())
                            .isActive(true)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return departmentRepository.save(department);
                })
                .map(this::toDepartmentResponse);
    }

    /**
     * Get all departments for an organization
     */
    public Flux<DepartmentResponse> getAllDepartments(Long organizationId) {
        return departmentRepository.findByOrganizationId(organizationId)
                .map(this::toDepartmentResponse);
    }

    /**
     * Get department by ID
     */
    public Mono<DepartmentResponse> getDepartmentById(Long id, Long organizationId) {
        return departmentRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Department not found")))
                .map(this::toDepartmentResponse);
    }

    /**
     * Update department
     */
    public Mono<DepartmentResponse> updateDepartment(Long id, CreateDepartmentRequest request, Long organizationId) {
        return departmentRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Department not found")))
                .flatMap(department -> {
                    department.setName(request.getName());
                    department.setDescription(request.getDescription());
                    department.setUpdatedAt(LocalDateTime.now());
                    return departmentRepository.save(department);
                })
                .map(this::toDepartmentResponse);
    }

    /**
     * Delete department
     */
    public Mono<Void> deleteDepartment(Long id, Long organizationId) {
        return departmentRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Department not found")))
                .flatMap(departmentRepository::delete);
    }

    /**
     * Convert Department to DepartmentResponse
     */
    private DepartmentResponse toDepartmentResponse(Department department) {
        return DepartmentResponse.builder()
                .id(department.getId())
                .organizationId(department.getOrganizationId())
                .name(department.getName())
                .description(department.getDescription())
                .isActive(department.getIsActive())
                .createdAt(department.getCreatedAt())
                .updatedAt(department.getUpdatedAt())
                .build();
    }
}
