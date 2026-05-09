package com.airral.controller;

import com.airral.dto.request.CreateDepartmentRequest;
import com.airral.dto.response.DepartmentResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.DepartmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/departments")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class DepartmentController {

    private final DepartmentService departmentService;
    private final JwtTokenProvider jwtTokenProvider;

    public DepartmentController(DepartmentService departmentService, JwtTokenProvider jwtTokenProvider) {
        this.departmentService = departmentService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Create a new department
     * POST /api/departments
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<DepartmentResponse>> createDepartment(
            @Valid @RequestBody CreateDepartmentRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return departmentService.createDepartment(request, organizationId)
                .map(dept -> ResponseEntity.status(HttpStatus.CREATED).body(dept));
    }

    /**
     * Get all departments
     * GET /api/departments
     */
    @GetMapping
    public Mono<ResponseEntity<Flux<DepartmentResponse>>> getAllDepartments(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(departmentService.getAllDepartments(organizationId)));
    }

    /**
     * Get department by ID
     * GET /api/departments/{id}
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<DepartmentResponse>> getDepartmentById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return departmentService.getDepartmentById(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Update department
     * PUT /api/departments/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<DepartmentResponse>> updateDepartment(
            @PathVariable Long id,
            @Valid @RequestBody CreateDepartmentRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return departmentService.updateDepartment(id, request, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Delete department
     * DELETE /api/departments/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Void>> deleteDepartment(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return departmentService.deleteDepartment(id, organizationId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /**
     * Helper method to extract JWT token from Authorization header
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
