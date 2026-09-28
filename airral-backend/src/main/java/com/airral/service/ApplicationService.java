package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.dto.response.ApplicationResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.CandidateProfileRepository;
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
    private final CandidateProfileRepository candidateProfileRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                            JobRepository jobRepository,
                            UserRepository userRepository,
                            OrganizationRepository organizationRepository,
                            CandidateProfileRepository candidateProfileRepository) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.candidateProfileRepository = candidateProfileRepository;
    }

    /**
     * An applicant applying for themselves.
     *
     * <p>The application is filed under the caller's own account and address,
     * whatever the request names, and only against a job candidates can see:
     * OPEN, at a company AIRRAL has verified. Any other job answers "Job not
     * found", the same as one that does not exist. It carries the resume the
     * applicant has on file, never a link the request names, and one person
     * applies to a job once.
     */
    public Mono<ApplicationResponse> applyAsApplicant(SubmitApplicationRequest request,
                                                      Long applicantId, String applicantEmail) {
        Mono<Job> job = jobRepository.findById(request.getJobId())
                .filter(found -> found.getStatus() == JobStatus.OPEN)
                .filterWhen(found -> organizationRepository.findById(found.getOrganizationId())
                        .map(CompanyVerificationService::isPublishable)
                        .defaultIfEmpty(false))
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(found -> applicationRepository.existsByJobIdAndApplicantId(found.getId(), applicantId)
                        .flatMap(applied -> applied
                                ? Mono.<Job>error(new ConflictException("You have already applied to this job"))
                                : Mono.just(found)));
        return job.zipWith(resumeOnFile(applicantId))
                .flatMap(found -> create(Mono.just(found.getT1()), request, applicantId, applicantEmail,
                        null, found.getT2()));
    }

    /** The applicant's active resume. Applying needs one: the company reviews it. */
    private Mono<Long> resumeOnFile(Long applicantId) {
        return candidateProfileRepository.findByUserId(applicantId)
                .mapNotNull(profile -> profile.getActiveResumeDocumentId())
                .switchIfEmpty(Mono.error(new BadRequestException("Upload your resume before you apply")));
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
        return create(job, request, null, request.getApplicantEmail(), request.getResumeUrl(), null);
    }

    /**
     * The applicant and resume document behind an application, for the company
     * reviewing it: only in the caller's company and jobs, and only when a resume
     * was attached.
     */
    public Mono<Application> applicationWithResume(Long id, Long organizationId, JobScope scope) {
        return applicationRepository.findByIdAndOrganizationId(id, organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .filter(application -> application.getResumeDocumentId() != null && application.getApplicantId() != null)
                .switchIfEmpty(Mono.error(new NotFoundException("No resume is attached to this application")));
    }

    private Mono<ApplicationResponse> create(Mono<Job> jobLookup, SubmitApplicationRequest request,
                                             Long applicantId, String applicantEmail,
                                             String resumeUrl, Long resumeDocumentId) {
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
                            .resumeUrl(resumeUrl)
                            .resumeDocumentId(resumeDocumentId)
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
                                    .resumeOnFile(application.getResumeDocumentId() != null)
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
