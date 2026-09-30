package com.airral.service;

import com.airral.domain.Department;
import com.airral.dto.request.CreateDepartmentRequest;
import com.airral.dto.response.DepartmentResponse;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final UserInvitationRepository invitationRepository;

    public DepartmentService(DepartmentRepository departmentRepository,
                             JobRepository jobRepository,
                             UserRepository userRepository,
                             UserInvitationRepository invitationRepository) {
        this.departmentRepository = departmentRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
    }

    /**
     * Create a new department
     */
    public Mono<DepartmentResponse> createDepartment(CreateDepartmentRequest request, Long organizationId) {
        // Check if department name already exists
        String name = request.getName().trim();
        return departmentRepository.existsByOrganizationIdAndName(organizationId, name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ConflictException("Department with this name already exists"));
                    }

                    Department department = Department.builder()
                            .organizationId(organizationId)
                            .name(name)
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
        String name = request.getName().trim();
        return departmentRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Department not found")))
                .flatMap(department -> department.getName().equalsIgnoreCase(name)
                        ? Mono.just(department)
                        : departmentRepository.existsByOrganizationIdAndName(organizationId, name)
                                .flatMap(taken -> taken
                                        ? Mono.<Department>error(new ConflictException("Department with this name already exists"))
                                        : Mono.just(department)))
                .flatMap(department -> {
                    department.setName(name);
                    department.setDescription(request.getDescription());
                    department.setUpdatedAt(LocalDateTime.now());
                    return departmentRepository.save(department);
                })
                // Jobs and people carry the department's name as well as its id,
                // and the public job board filters on the name, so a rename has
                // to reach them.
                .flatMap(saved -> jobRepository.setDepartmentName(saved.getId(), saved.getName())
                        .then(userRepository.setDepartmentName(saved.getId(), saved.getName()))
                        .then(invitationRepository.setDepartmentName(saved.getId(), saved.getName()))
                        .thenReturn(saved))
                .map(this::toDepartmentResponse);
    }

    /**
     * Delete department. Its jobs and people keep existing, with no department.
     */
    public Mono<Void> deleteDepartment(Long id, Long organizationId) {
        return departmentRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Department not found")))
                .flatMap(department -> jobRepository.clearDepartment(department.getId())
                        .then(userRepository.clearDepartment(department.getId()))
                        .then(invitationRepository.clearDepartment(department.getId()))
                        .then(departmentRepository.delete(department)));
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
