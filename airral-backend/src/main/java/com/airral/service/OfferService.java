package com.airral.service;

import com.airral.domain.Offer;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.OfferStatus;
import com.airral.dto.request.CreateOfferRequest;
import com.airral.dto.response.OfferResponse;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.OfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class OfferService {

    private final OfferRepository offerRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;

    public OfferService(OfferRepository offerRepository,
                       ApplicationRepository applicationRepository,
                       JobRepository jobRepository) {
        this.offerRepository = offerRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
    }

    /**
     * Create a new offer
     */
    public Mono<OfferResponse> createOffer(CreateOfferRequest request, Long organizationId) {
        // Verify the application belongs to this organization
        return applicationRepository.findByIdAndOrganizationId(request.getApplicationId(), organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(application -> {
                    Offer offer = Offer.builder()
                            .applicationId(request.getApplicationId())
                            .jobId(request.getJobId())
                            .salary(request.getSalary())
                            .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                            .startDate(request.getStartDate())
                            .offerLetter(request.getOfferLetter())
                            .benefits(request.getBenefits())
                            .contingencies(request.getContingencies())
                            .status(OfferStatus.DRAFT)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return offerRepository.save(offer);
                })
                .flatMap(this::toOfferResponse);
    }

    /**
     * Get offer by ID
     */
    public Mono<OfferResponse> getOfferById(Long id, Long organizationId) {
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(this::toOfferResponse);
    }

    /**
     * Get all offers for an organization
     */
    public Flux<OfferResponse> getAllOffers(Long organizationId) {
        return offerRepository.findAllByOrganizationId(organizationId)
                .flatMap(this::toOfferResponse);
    }

    /**
     * Get offers by application
     */
    public Flux<OfferResponse> getOffersByApplication(Long applicationId, Long organizationId) {
        // Verify the application belongs to this organization
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .flatMapMany(app -> offerRepository.findByApplicationId(applicationId))
                .flatMap(this::toOfferResponse);
    }

    /**
     * Send offer to candidate
     */
    @Transactional
    public Mono<OfferResponse> sendOffer(Long id, Long organizationId) {
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(offer -> {
                    offer.setStatus(OfferStatus.SENT);
                    offer.setSentAt(LocalDateTime.now());
                    // Set expiry to 7 days from now
                    offer.setExpiresAt(LocalDateTime.now().plusDays(7));
                    offer.setUpdatedAt(LocalDateTime.now());

                    // Update application status
                    return applicationRepository.findById(offer.getApplicationId())
                            .flatMap(application -> {
                                application.setStatus(ApplicationStatus.OFFER_EXTENDED);
                                application.setUpdatedAt(LocalDateTime.now());
                                return applicationRepository.save(application);
                            })
                            .then(offerRepository.save(offer));
                })
                .flatMap(this::toOfferResponse);
    }

    /**
     * Update offer status (accept/decline)
     */
    @Transactional
    public Mono<OfferResponse> updateOfferStatus(Long id, OfferStatus status, Long organizationId) {
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(offer -> {
                    offer.setStatus(status);
                    offer.setRespondedAt(LocalDateTime.now());
                    offer.setUpdatedAt(LocalDateTime.now());

                    // If accepted, update application to HIRED
                    if (status == OfferStatus.ACCEPTED) {
                        return applicationRepository.findById(offer.getApplicationId())
                                .flatMap(application -> {
                                    application.setStatus(ApplicationStatus.HIRED);
                                    application.setUpdatedAt(LocalDateTime.now());
                                    return applicationRepository.save(application);
                                })
                                .then(offerRepository.save(offer));
                    }

                    return offerRepository.save(offer);
                })
                .flatMap(this::toOfferResponse);
    }

    /**
     * Convert Offer entity to OfferResponse DTO
     */
    private Mono<OfferResponse> toOfferResponse(Offer offer) {
        return applicationRepository.findById(offer.getApplicationId())
                .flatMap(application -> 
                    jobRepository.findById(application.getJobId())
                            .map(job -> OfferResponse.builder()
                                    .id(offer.getId())
                                    .applicationId(offer.getApplicationId())
                                    .jobId(offer.getJobId())
                                    .candidateName(application.getApplicantName())
                                    .candidateEmail(application.getApplicantEmail())
                                    .jobTitle(job.getTitle())
                                    .salary(offer.getSalary())
                                    .currency(offer.getCurrency())
                                    .startDate(offer.getStartDate())
                                    .offerLetter(offer.getOfferLetter())
                                    .benefits(offer.getBenefits())
                                    .contingencies(offer.getContingencies())
                                    .status(offer.getStatus())
                                    .sentAt(offer.getSentAt())
                                    .expiresAt(offer.getExpiresAt())
                                    .respondedAt(offer.getRespondedAt())
                                    .createdAt(offer.getCreatedAt())
                                    .updatedAt(offer.getUpdatedAt())
                                    .build())
                );
    }
}
