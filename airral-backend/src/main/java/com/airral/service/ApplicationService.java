package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.dto.response.ApplicationResponse;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
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
    private final OrganizationRepository organizationRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                            JobRepository jobRepository,
                            UserRepository userRepository,
                            OrganizationRepository organizationRepository) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * An applicant applying for themselves.
     *
     * <p>The application is filed under the caller's own account and address,
     * whatever the request names, and only against a job candidates can see:
     * OPEN, at a company AIRRAL has verified. Any other job answers "Job not
     * found", the same as one that does not exist.
     */
    public Mono<ApplicationResponse> applyAsApplicant(SubmitApplicationRequest request,
                                                      Long applicantId, String applicantEmail) {
        Mono<Job> job = jobRepository.findById(request.getJobId())
                .filter(found -> found.getStatus() == JobStatus.OPEN)
                .filterWhen(found -> organizationRepository.findById(found.getOrganizationId())
                        .map(CompanyVerificationService::isPublishable)
                        .defaultIfEmpty(false))
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")));
        return create(job, request, applicantId, applicantEmail);
    }

    /**
     * HR adding a candidate by hand, to one of its own company's jobs.
     *
     * <p>The application is linked to no AIRRAL account: HR can name a
     * candidate, but cannot file an application under somebody's account.
     */
    public Mono<ApplicationResponse> addCandidate(SubmitApplicationRequest request, Long organizationId) {
        Mono<Job> job = jobRepository.findById(request.getJobId())
                .filter(found -> organizationId != null && organizationId.equals(found.getOrganizationId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")));
        return create(job, request, null, request.getApplicantEmail());
    }

    private Mono<ApplicationResponse> create(Mono<Job> jobLookup, SubmitApplicationRequest request,
                                             Long applicantId, String applicantEmail) {
        return jobLookup
                .flatMap(job -> {
                    // Calculate ATS score
                    int atsScore = calculateAtsScore(job, request.getCoverLetter());
                    
                    Application application = Application.builder()
                            .jobId(request.getJobId())
                            .applicantId(applicantId)
                            .applicantName(request.getApplicantName())
                            .applicantEmail(applicantEmail)
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
                    if (job.getAtsKeywords() != null && job.getAtsKeywords().length > 0) {
                        String coverText = request.getCoverLetter() != null ? 
                                request.getCoverLetter().toLowerCase() : "";
                        List<String> keywords = Arrays.asList(job.getAtsKeywords());
                        
                        List<String> matched = keywords.stream()
                                .filter(kw -> coverText.contains(kw.toLowerCase()))
                                .collect(Collectors.toList());
                        
                        List<String> missing = keywords.stream()
                                .filter(kw -> !coverText.contains(kw.toLowerCase()))
                                .collect(Collectors.toList());
                        
                        application.setAtsMatchedKeywords(matched.toArray(String[]::new));
                        application.setAtsMissingKeywords(missing.toArray(String[]::new));
                    }

                    return applicationRepository.save(application);
                })
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get application by ID
     */
    public Mono<ApplicationResponse> getApplicationById(Long id, Long organizationId, JobScope scope) {
        return applicationRepository.findByIdAndOrganizationId(id, organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get all applications for an organization
     */
    public Flux<ApplicationResponse> getAllApplications(Long organizationId, JobScope scope) {
        return applicationRepository.findAllByOrganizationId(organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .flatMap(this::toApplicationResponse);
    }

    /**
     * Get applications by job
     */
    public Flux<ApplicationResponse> getApplicationsByJob(Long jobId, Long organizationId, JobScope scope) {
        if (!scope.allows(jobId)) {
            return Flux.empty();
        }
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
                                                             Long organizationId, Long userId, JobScope scope) {
        return applicationRepository.findByIdAndOrganizationId(id, organizationId)
                .filter(application -> scope.allows(application.getJobId()))
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
        if (job.getAtsKeywords() == null || job.getAtsKeywords().length == 0) {
            return 75; // Default score if no keywords configured
        }

        String coverText = coverLetter != null ? coverLetter.toLowerCase() : "";
        List<String> keywords = Arrays.asList(job.getAtsKeywords());
        
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
                            Mono.just("");

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
                                            Arrays.asList(application.getAtsMatchedKeywords()) : null)
                                    .atsMissingKeywords(application.getAtsMissingKeywords() != null ?
                                            Arrays.asList(application.getAtsMissingKeywords()) : null)
                                    .visibleToHr(application.getVisibleToHr())
                                    .reviewedBy(reviewedBy.isBlank() ? null : reviewedBy)
                                    .reviewedByHrAt(application.getReviewedByHrAt())
                                    .appliedAt(application.getAppliedAt())
                                    .updatedAt(application.getUpdatedAt())
                                    .build()
                    );
                });
    }
}
