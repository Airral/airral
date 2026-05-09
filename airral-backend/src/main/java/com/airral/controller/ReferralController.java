package com.airral.controller;

import com.airral.dto.request.CreateReferralRequest;
import com.airral.dto.response.ReferralResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.ReferralService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/referrals")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class ReferralController {

    private final ReferralService referralService;
    private final JwtTokenProvider jwtTokenProvider;

    public ReferralController(ReferralService referralService, JwtTokenProvider jwtTokenProvider) {
        this.referralService = referralService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Submit a referral
     * POST /api/referrals
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<ReferralResponse>> submitReferral(
            @Valid @RequestBody CreateReferralRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return referralService.submitReferral(request, organizationId, userId)
                .map(referral -> ResponseEntity.status(HttpStatus.CREATED).body(referral));
    }

    /**
     * Get my referrals
     * GET /api/referrals
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ReferralResponse>>> getMyReferrals(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return Mono.just(ResponseEntity.ok(referralService.getMyReferrals(userId)));
    }

    /**
     * Get all referrals for organization (HR only)
     * GET /api/referrals/organization
     */
    @GetMapping("/organization")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ReferralResponse>>> getAllReferrals(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(referralService.getAllReferrals(organizationId)));
    }

    /**
     * Get referral by ID
     * GET /api/referrals/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<ReferralResponse>> getReferralById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return referralService.getReferralById(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Update referral status (approve/reject)
     * PUT /api/referrals/{id}/status?status=APPROVED
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<ReferralResponse>> updateReferralStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return referralService.updateReferralStatus(id, status, organizationId, userId)
                .map(ResponseEntity::ok);
    }

    /**
     * Convert referral to application
     * POST /api/referrals/{id}/application
     */
    @PostMapping("/{id}/application")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<ReferralResponse>> convertToApplication(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return referralService.convertToApplication(id, organizationId)
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
