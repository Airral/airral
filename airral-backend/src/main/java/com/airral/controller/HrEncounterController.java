package com.airral.controller;

import com.airral.dto.request.CreateEncounterRequest;
import com.airral.dto.response.EncounterResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.HrEncounterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/encounters")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class HrEncounterController {

    private final HrEncounterService encounterService;
    private final JwtTokenProvider jwtTokenProvider;

    public HrEncounterController(HrEncounterService encounterService, JwtTokenProvider jwtTokenProvider) {
        this.encounterService = encounterService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Create a new encounter
     * POST /api/encounters
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<EncounterResponse>> createEncounter(
            @Valid @RequestBody CreateEncounterRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return encounterService.createEncounter(request, organizationId, userId)
                .map(encounter -> ResponseEntity.status(HttpStatus.CREATED).body(encounter));
    }

    /**
     * Get all encounters for organization
     * GET /api/encounters
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<EncounterResponse>>> getAllEncounters(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(encounterService.getAllEncounters(organizationId)));
    }

    /**
     * Get encounters for an application (timeline)
     * GET /api/encounters/application/{applicationId}
     */
    @GetMapping("/application/{applicationId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<EncounterResponse>>> getEncountersByApplication(
            @PathVariable Long applicationId) {

        return Mono.just(ResponseEntity.ok(encounterService.getEncountersByApplication(applicationId)));
    }

    /**
     * Get encounters for a job
     * GET /api/encounters/job/{jobId}
     */
    @GetMapping("/job/{jobId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<EncounterResponse>>> getEncountersByJob(
            @PathVariable Long jobId) {

        return Mono.just(ResponseEntity.ok(encounterService.getEncountersByJob(jobId)));
    }

    /**
     * Get recent encounters
     * GET /api/encounters/recent?limit=50
     */
    @GetMapping("/recent")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<EncounterResponse>>> getRecentEncounters(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(encounterService.getRecentEncounters(organizationId, limit)));
    }

    /**
     * Get encounters by type
     * GET /api/encounters/type/{encounterType}
     */
    @GetMapping("/type/{encounterType}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<EncounterResponse>>> getEncountersByType(
            @PathVariable String encounterType,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(encounterService.getEncountersByType(organizationId, encounterType)));
    }

    /**
     * Get encounter by ID
     * GET /api/encounters/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<EncounterResponse>> getEncounterById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return encounterService.getEncounterById(id, organizationId)
                .map(ResponseEntity::ok);
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
