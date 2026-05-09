package com.airral.controller;

import com.airral.domain.enums.OfferStatus;
import com.airral.dto.request.CreateOfferRequest;
import com.airral.dto.response.OfferResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.OfferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/offers")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class OfferController {

    private final OfferService offerService;
    private final JwtTokenProvider jwtTokenProvider;

    public OfferController(OfferService offerService, JwtTokenProvider jwtTokenProvider) {
        this.offerService = offerService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Create a new offer
     * POST /api/offers
     * Requires: HR_MANAGER or ADMIN role
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> createOffer(
            @Valid @RequestBody CreateOfferRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return offerService.createOffer(request, organizationId)
                .map(offer -> ResponseEntity.status(HttpStatus.CREATED).body(offer));
    }

    /**
     * Get all offers for the organization
     * GET /api/offers
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<OfferResponse>>> getAllOffers(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(offerService.getAllOffers(organizationId)));
    }

    /**
     * Get offer by ID
     * GET /api/offers/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<OfferResponse>> getOfferById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return offerService.getOfferById(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Get offers by application
     * GET /api/offers/application/{applicationId}
     */
    @GetMapping("/application/{applicationId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<Flux<OfferResponse>>> getOffersByApplication(
            @PathVariable Long applicationId,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(
                offerService.getOffersByApplication(applicationId, organizationId)
        ));
    }

    /**
     * Send offer to candidate
     * PUT /api/offers/{id}/send
     */
    @PutMapping("/{id}/send")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> sendOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return offerService.sendOffer(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Update offer status (accept/decline)
     * PUT /api/offers/{id}/status?status=ACCEPTED
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<OfferResponse>> updateOfferStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        try {
            OfferStatus offerStatus = OfferStatus.valueOf(status.toUpperCase());
            return offerService.updateOfferStatus(id, offerStatus, organizationId)
                    .map(ResponseEntity::ok);
        } catch (IllegalArgumentException e) {
            return Mono.error(new BadRequestException("Invalid offer status: " + status));
        }
    }

    /**
     * Accept offer (convenience endpoint)
     * POST /api/offers/{id}/accept
     */
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<OfferResponse>> acceptOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return offerService.updateOfferStatus(id, OfferStatus.ACCEPTED, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Decline offer (convenience endpoint)
     * POST /api/offers/{id}/decline
     */
    @PostMapping("/{id}/decline")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<OfferResponse>> declineOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return offerService.updateOfferStatus(id, OfferStatus.DECLINED, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Withdraw offer (convenience endpoint)
     * POST /api/offers/{id}/withdraw
     */
    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> withdrawOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return offerService.updateOfferStatus(id, OfferStatus.WITHDRAWN, organizationId)
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
