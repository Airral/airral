package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Referral;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.dto.request.CreateReferralRequest;
import com.airral.dto.response.ReferralResponse;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.ReferralRepository;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class ReferralService {

    private final ReferralRepository referralRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public ReferralService(ReferralRepository referralRepository,
                          ApplicationRepository applicationRepository,
                          JobRepository jobRepository,
                          UserRepository userRepository) {
        this.referralRepository = referralRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    /**
     * Submit a referral
     */
    public Mono<ReferralResponse> submitReferral(CreateReferralRequest request, Long organizationId, Long userId) {
        Referral referral = Referral.builder()
                .organizationId(organizationId)
                .referredById(userId)
                .referredName(request.getReferredName())
                .referredEmail(request.getReferredEmail())
                .referredPhone(request.getReferredPhone())
                .jobId(request.getJobId())
                .notes(request.getNotes())
                .relationship(request.getRelationship())
                .resumeUrl(request.getResumeUrl())
                .status("PENDING")
                .submittedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return referralRepository.save(referral)
                .flatMap(this::toReferralResponse);
    }

    /**
     * Get all referrals for an organization
     */
    public Flux<ReferralResponse> getAllReferrals(Long organizationId) {
        return referralRepository.findByOrganizationId(organizationId)
                .flatMap(this::toReferralResponse);
    }

    /**
     * Get my referrals (by user)
     */
    public Flux<ReferralResponse> getMyReferrals(Long userId) {
        return referralRepository.findByReferredById(userId)
                .flatMap(this::toReferralResponse);
    }

    /**
     * Get referral by ID
     */
    public Mono<ReferralResponse> getReferralById(Long id, Long organizationId) {
        return referralRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Referral not found")))
                .flatMap(this::toReferralResponse);
    }

    /**
     * Update referral status (approve/reject)
     */
    public Mono<ReferralResponse> updateReferralStatus(Long id, String status, Long organizationId, Long reviewerId) {
        return referralRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Referral not found")))
                .flatMap(referral -> {
                    referral.setStatus(status.toUpperCase());
                    referral.setReviewedAt(LocalDateTime.now());
                    referral.setReviewedById(reviewerId);
                    referral.setUpdatedAt(LocalDateTime.now());

                    return referralRepository.save(referral);
                })
                .flatMap(this::toReferralResponse);
    }

    /**
     * Convert referral to application
     */
        @Transactional
    public Mono<ReferralResponse> convertToApplication(Long id, Long organizationId) {
        return referralRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Referral not found")))
                .flatMap(referral -> {
                    if (!"APPROVED".equals(referral.getStatus())) {
                        return Mono.error(new BadRequestException("Only approved referrals can be converted to applications"));
                    }

                    if (referral.getApplicationId() != null) {
                        return Mono.error(new ConflictException("Referral already converted to application"));
                    }

                    // Create application
                    Application application = Application.builder()
                            .jobId(referral.getJobId())
                            .applicantName(referral.getReferredName())
                            .applicantEmail(referral.getReferredEmail())
                            .applicantPhone(referral.getReferredPhone())
                            .resumeUrl(referral.getResumeUrl())
                            .coverLetter("Referred by employee")
                            .status(ApplicationStatus.SUBMITTED)
                            .atsScore(100) // Referrals get high score
                            .visibleToHr(true)
                            .appliedAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return applicationRepository.save(application)
                            .flatMap(app -> {
                                referral.setApplicationId(app.getId());
                                referral.setUpdatedAt(LocalDateTime.now());
                                return referralRepository.save(referral);
                            });
                })
                .flatMap(this::toReferralResponse);
    }

    /**
     * Convert Referral to ReferralResponse DTO
     */
    private Mono<ReferralResponse> toReferralResponse(Referral referral) {
        Mono<String> referredByNameMono = userRepository.findById(referral.getReferredById())
                .map(user -> user.getFullName())
                .defaultIfEmpty("Unknown");

        Mono<String> jobTitleMono = referral.getJobId() != null ?
                jobRepository.findById(referral.getJobId())
                        .map(job -> job.getTitle())
                        .defaultIfEmpty("Unknown") :
                Mono.just(null);

        Mono<String> reviewedByMono = referral.getReviewedById() != null ?
                userRepository.findById(referral.getReviewedById())
                        .map(user -> user.getFullName())
                        .defaultIfEmpty("Unknown") :
                Mono.just(null);

        return Mono.zip(referredByNameMono, jobTitleMono, reviewedByMono)
                .map(tuple -> ReferralResponse.builder()
                        .id(referral.getId())
                        .organizationId(referral.getOrganizationId())
                        .referredById(referral.getReferredById())
                        .referredByName(tuple.getT1())
                        .referredName(referral.getReferredName())
                        .referredEmail(referral.getReferredEmail())
                        .referredPhone(referral.getReferredPhone())
                        .jobId(referral.getJobId())
                        .jobTitle(tuple.getT2())
                        .notes(referral.getNotes())
                        .relationship(referral.getRelationship())
                        .resumeUrl(referral.getResumeUrl())
                        .status(referral.getStatus())
                        .reviewedAt(referral.getReviewedAt())
                        .reviewedBy(tuple.getT3())
                        .applicationId(referral.getApplicationId())
                        .bonusAmount(referral.getBonusAmount())
                        .bonusPaidAt(referral.getBonusPaidAt())
                        .submittedAt(referral.getSubmittedAt())
                        .updatedAt(referral.getUpdatedAt())
                        .build()
                );
    }
}
