package com.airral.controller;

import com.airral.domain.enums.UserRole;
import com.airral.dto.request.CreateOfferRequest;
import com.airral.dto.request.SendOfferRequest;
import com.airral.dto.response.OfferResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.OfferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Offers. HR drafts, sends and withdraws them. The candidate answers: an
 * applicant from their own account, or, for a candidate HR added by hand, HR
 * records the answer.
 */
@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final OfferService offerService;
    private final JwtTokenProvider jwtTokenProvider;

    public OfferController(OfferService offerService, JwtTokenProvider jwtTokenProvider) {
        this.offerService = offerService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Draft an offer
     * POST /api/offers
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> createOffer(
            @Valid @RequestBody CreateOfferRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return offerService.createOffer(request, companyOf(authHeader))
                .map(offer -> ResponseEntity.status(HttpStatus.CREATED).body(offer));
    }

    /**
     * The company's offers
     * GET /api/offers
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<OfferResponse>>> getAllOffers(@RequestHeader("Authorization") String authHeader) {
        return Mono.just(ResponseEntity.ok(offerService.getAllOffers(companyOf(authHeader))));
    }

    /**
     * The signed-in applicant's own offers, once sent
     * GET /api/offers/mine
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('APPLICANT')")
    public Mono<ResponseEntity<Flux<OfferResponse>>> getMyOffers(@RequestHeader("Authorization") String authHeader) {
        return Mono.just(ResponseEntity.ok(offerService.getMyOffers(
                jwtTokenProvider.getUserIdFromToken(extractToken(authHeader)))));
    }

    /**
     * One of the company's offers
     * GET /api/offers/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> getOfferById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {

        return offerService.getOfferById(id, companyOf(authHeader)).map(ResponseEntity::ok);
    }

    /**
     * The offers on one of the company's applications
     * GET /api/offers/application/{applicationId}
     */
    @GetMapping("/application/{applicationId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<OfferResponse>>> getOffersByApplication(
            @PathVariable Long applicationId,
            @RequestHeader("Authorization") String authHeader) {

        return Mono.just(ResponseEntity.ok(offerService.getOffersByApplication(applicationId, companyOf(authHeader))));
    }

    /**
     * Send a draft to the candidate. The HR portal has always sent a POST here,
     * which the API refused, so no offer could ever be sent; both methods work.
     * POST or PUT /api/offers/{id}/send
     */
    @RequestMapping(value = "/{id}/send", method = {RequestMethod.POST, RequestMethod.PUT})
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> sendOffer(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SendOfferRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return offerService.sendOffer(id, companyOf(authHeader), request == null ? null : request.getExpiresInDays())
                .map(ResponseEntity::ok);
    }

    /**
     * Accept an offer: the applicant's own, or, for a candidate HR added by
     * hand, HR recording their answer.
     * POST /api/offers/{id}/accept
     */
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<OfferResponse>> acceptOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        return answer(id, authHeader, true);
    }

    /**
     * Decline an offer, the same way.
     * POST /api/offers/{id}/decline
     */
    @PostMapping("/{id}/decline")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'APPLICANT')")
    public Mono<ResponseEntity<OfferResponse>> declineOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        return answer(id, authHeader, false);
    }

    /**
     * Withdraw an offer that has not been answered
     * POST /api/offers/{id}/withdraw
     */
    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<OfferResponse>> withdrawOffer(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {

        return offerService.withdrawOffer(id, companyOf(authHeader)).map(ResponseEntity::ok);
    }

    private Mono<ResponseEntity<OfferResponse>> answer(Long id, String authHeader, boolean accept) {
        String token = extractToken(authHeader);
        Mono<OfferResponse> answered = UserRole.APPLICANT.name().equals(jwtTokenProvider.getRoleFromToken(token))
                ? offerService.answerAsApplicant(id, jwtTokenProvider.getUserIdFromToken(token), accept)
                : offerService.recordAnswer(id, jwtTokenProvider.getOrganizationIdFromToken(token), accept);
        return answered.map(ResponseEntity::ok);
    }

    private Long companyOf(String authHeader) {
        return jwtTokenProvider.getOrganizationIdFromToken(extractToken(authHeader));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
