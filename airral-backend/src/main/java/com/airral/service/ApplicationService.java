package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.dto.response.ApplicationResponse;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                            JobRepository jobRepository,
                            UserRepository userRepository) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    /**
     * Submit a new application
     */
    public Mono<ApplicationResponse> submitApplication(SubmitApplicationRequest request) {
        return jobRepository.findById(request.getJobId())
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(job -> {
                    // Calculate ATS score
                    int atsScore = calculateAtsScore(job, request.getCoverLetter());
                    
                    Application application = Application.builder()
                            .jobId(request.getJobId())
                            .applicantId(request.getApplicantId())
                            .applicantName(request.getApplicantName())
                            .applicantEmail(request.getApplicantEmail())
                            .applicantPhone(request.getApplicantPhone())
                            .resumeUrl(request.getResumeUrl())
                            .coverLetter(request.getCoverLetter())
                            .status(ApplicationStatus.SUBMITTED)
                            .atsScore(atsScore)
                            .visibleToHr(atsScore >= (job.getAtsMinScore() != null ? job.getAtsMinScore() : 70))
                            .appliedAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    // Calculate matched/missing keywords
                    if (job.getAtsKeywords() != null && !job.getAtsKeywords().isEmpty()) {
                        String coverText = request.getCoverLetter() != null ? 
                                request.getCoverLetter().toLowerCase() : "";
                        List<String> keywords = Arrays.asList(job.getAtsKeywords().split(","));
                        
                        List<String> matched = keywords.stream()
                                .filter(kw -> coverText.contains(kw.toLowerCase()))
                                .collect(Collectors.toList());
                        
                        List<String> missing = keywords.stream()
                                .filter(kw -> !coverText.contains(kw.toLowerCase()))
                                .collect(Collectors.toList());
                        
                        application.setAtsMatchedKeywords(String.join(",", matched));
                        application.setAtsMissingKeywords(String.join(",", missing));
                    }

                    return applicationRepository.save(application);
                })
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get application by ID
     */
    public Mono<ApplicationResponse> getApplicationById(Long id, Long organizationId) {
        return applicationRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get all applications for an organization
     */
    public Flux<ApplicationResponse> getAllApplications(Long organizationId) {
        return applicationRepository.findAllByOrganizationId(organizationId)
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get applications by job
     */
    public Flux<ApplicationResponse> getApplicationsByJob(Long jobId, Long organizationId) {
        return applicationRepository.findByJobIdAndOrganizationId(jobId, organizationId)
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get applications by applicant
     */
    public Flux<ApplicationResponse> getMyApplications(Long applicantId) {
        return applicationRepository.findByApplicantId(applicantId)
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Update application status
     */
    public Mono<ApplicationResponse> updateApplicationStatus(Long id, ApplicationStatus status, 
                                                             Long organizationId, Long userId) {
        return applicationRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(application -> {
                    application.setStatus(status);
                    application.setUpdatedAt(LocalDateTime.now());
                    
                    // Track who reviewed it
                    if (status == ApplicationStatus.UNDER_REVIEW && application.getReviewedByHrId() == null) {
                        application.setReviewedByHrId(userId);
                        application.setReviewedByHrAt(LocalDateTime.now());
                    }
                    
                    return applicationRepository.save(application);
                })
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Calculate ATS score based on job requirements
     * Basic implementation - can be enhanced with ML/AI
     */
    private int calculateAtsScore(Job job, String coverLetter) {
        if (job.getAtsKeywords() == null || job.getAtsKeywords().isEmpty()) {
            return 75; // Default score if no keywords configured
        }

        String coverText = coverLetter != null ? coverLetter.toLowerCase() : "";
        List<String> keywords = Arrays.asList(job.getAtsKeywords().split(","));
        
        if (keywords.isEmpty()) {
            return 75;
        }

        long matchedCount = keywords.stream()
                .filter(kw -> coverText.contains(kw.toLowerCase().trim()))
                .count();

        // Calculate percentage match
        return (int) ((matchedCount * 100.0) / keywords.size());
    }

    /**
     * Convert Application entity to ApplicationResponse DTO
     */
    private Mono<ApplicationResponse> toApplicationResponse(Application application) {
        return jobRepository.findById(application.getJobId())
                .flatMap(job -> {
                    Mono<String> reviewedByMono = application.getReviewedByHrId() != null ?
                            userRepository.findById(application.getReviewedByHrId())
                                    .map(user -> user.getFullName())
                                    .defaultIfEmpty("Unknown") :
                            Mono.just(null);

                    return reviewedByMono.map(reviewedBy ->
                            ApplicationResponse.builder()
                                    .id(application.getId())
                                    .jobId(application.getJobId())
                                    .jobTitle(job.getTitle())
                                    .applicantId(application.getApplicantId())
                                    .applicantName(application.getApplicantName())
                                    .applicantEmail(application.getApplicantEmail())
                                    .applicantPhone(application.getApplicantPhone())
                                    .resumeUrl(application.getResumeUrl())
                                    .coverLetter(application.getCoverLetter())
                                    .status(application.getStatus())
                                    .atsScore(application.getAtsScore())
                                    .atsMatchedKeywords(application.getAtsMatchedKeywords() != null ?
                                            Arrays.asList(application.getAtsMatchedKeywords().split(",")) : null)
                                    .atsMissingKeywords(application.getAtsMissingKeywords() != null ?
                                            Arrays.asList(application.getAtsMissingKeywords().split(",")) : null)
                                    .visibleToHr(application.getVisibleToHr())
                                    .reviewedBy(reviewedBy)
                                    .reviewedByHrAt(application.getReviewedByHrAt())
                                    .appliedAt(application.getAppliedAt())
                                    .updatedAt(application.getUpdatedAt())
                                    .build()
                    );
                });
    }
}
