package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.CandidateResumeDocument;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.ApplicantStage;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.dto.response.ApplicationResponse;
import com.airral.dto.response.MyApplicationResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OfferRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final CandidateProfileRepository candidateProfileRepository;
    private final CandidateUpdateEmails candidateEmails;
    private final CandidateResumeDocumentRepository resumeDocumentRepository;
    private final OfferRepository offerRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                            JobRepository jobRepository,
                            UserRepository userRepository,
                            OrganizationRepository organizationRepository,
                            CandidateProfileRepository candidateProfileRepository,
                            CandidateUpdateEmails candidateEmails,
                            CandidateResumeDocumentRepository resumeDocumentRepository,
                            OfferRepository offerRepository) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.candidateProfileRepository = candidateProfileRepository;
        this.candidateEmails = candidateEmails;
        this.resumeDocumentRepository = resumeDocumentRepository;
        this.offerRepository = offerRepository;
    }

    /**
     * An applicant applying for themselves.
     *
     * <p>The application is filed under the caller's own account and address,
     * whatever the request names, and only against a job candidates can see:
     * OPEN, at a company AIRRAL has verified. Any other job answers "Job not
     * found", the same as one that does not exist. It carries the resume the
     * applicant has on file, never a link the request names, and one person
     * applies to a job once. The applicant gets an email saying the company
     * has it.
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
                        null, found.getT2()))
                .doOnNext(saved -> candidateEmails.applicationReceived(applicationFor(saved)));
    }

    /** The fields of a new application its "received" email reads. */
    private static Application applicationFor(ApplicationResponse saved) {
        return Application.builder()
                .id(saved.getId())
                .jobId(saved.getJobId())
                .applicantId(saved.getApplicantId())
                .applicantName(saved.getApplicantName())
                .applicantEmail(saved.getApplicantEmail())
                .build();
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
        String email = request.getApplicantEmail().trim().toLowerCase(Locale.ROOT);
        Mono<Job> job = jobRepository.findById(request.getJobId())
                .filter(found -> organizationId != null && organizationId.equals(found.getOrganizationId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(found -> applicationRepository.existsByJobIdAndApplicantEmail(found.getId(), email)
                        .flatMap(taken -> taken
                                ? Mono.<Job>error(new ConflictException(email + " is already a candidate for this job"))
                                : Mono.just(found)));
        String resumeUrl = request.getResumeUrl() == null || request.getResumeUrl().isBlank()
                ? null : request.getResumeUrl().trim();
        return create(job, request, null, email, resumeUrl, null);
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
                .zipWith(applicationText(resumeDocumentId, applicantId, request.getCoverLetter()))
                .flatMap(found -> {
                    Job job = found.getT1();
                    JobAlignment.Text text = found.getT2();
                    // What the job asks for, read against the resume and the cover letter.
                    JobAlignment.Result alignment = JobAlignment.of(job, text.value());
                    int atsScore = alignment.score();

                    Application application = Application.builder()
                            .jobId(request.getJobId())
                            .applicantId(applicantId)
                            .applicantName(request.getApplicantName())
                            .applicantEmail(applicantEmail)
                            .applicantPhone(request.getApplicantPhone())
                            .resumeUrl(resumeUrl)
                            .resumeDocumentId(resumeDocumentId)
                            .alignmentSource(text.source().name())
                            .coverLetter(request.getCoverLetter())
                            .status(ApplicationStatus.SUBMITTED)
                            .atsScore(atsScore)
                            .visibleToHr(atsScore >= (job.getAtsMinScore() != null ? job.getAtsMinScore() : 70))
                            .appliedAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    if (!alignment.matched().isEmpty() || !alignment.missing().isEmpty()) {
                        application.setAtsMatchedKeywords(alignment.matched().toArray(String[]::new));
                        application.setAtsMissingKeywords(alignment.missing().toArray(String[]::new));
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
     * An applicant's own applications, newest first, with the stage each one
     * is at as the applicant sees it.
     */
    public Flux<MyApplicationResponse> getMyApplications(Long applicantId) {
        return applicationRepository.findByApplicantId(applicantId)
                .flatMapSequential(application -> jobRepository.findById(application.getJobId())
                        .flatMap(job -> organizationRepository.findById(job.getOrganizationId())
                                .map(Organization::getName)
                                .defaultIfEmpty("")
                                .map(companyName -> MyApplicationResponse.builder()
                                        .id(application.getId())
                                        .jobId(application.getJobId())
                                        .jobTitle(job.getTitle())
                                        .companyName(companyName.isBlank() ? null : companyName)
                                        .stage(ApplicantStage.of(application.getStatus()))
                                        .appliedAt(application.getAppliedAt())
                                        .updatedAt(application.getUpdatedAt())
                                        .build())));
    }

    /**
     * Move an application to a stage. Turning a candidate down can email them,
     * when the caller asks, once the change is saved.
     */
    @Transactional
    public Mono<ApplicationResponse> updateApplicationStatus(Long id, ApplicationStatus status,
                                                             Long organizationId, Long userId, JobScope scope) {
        return updateApplicationStatus(id, status, organizationId, userId, scope, false);
    }

    @Transactional
    public Mono<ApplicationResponse> updateApplicationStatus(Long id, ApplicationStatus status,
                                                             Long organizationId, Long userId, JobScope scope,
                                                             boolean notifyCandidate) {
        return applicationRepository.findByIdAndOrganizationId(id, organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(application -> {
                    ApplicationStatus previous = application.getStatus();
                    if (previous == status) {
                        return Mono.just(application);
                    }
                    boolean turnedDown = status == ApplicationStatus.REJECTED;
                    LocalDateTime now = LocalDateTime.now();
                    return offersFollow(application, status, now).then(Mono.defer(() -> {
                        application.setStatus(status);
                        application.setUpdatedAt(now);

                        // Track who reviewed it
                        if (status == ApplicationStatus.UNDER_REVIEW && application.getReviewedByHrId() == null) {
                            application.setReviewedByHrId(userId);
                            application.setReviewedByHrAt(now);
                        }

                        return applicationRepository.save(application)
                                .flatMap(saved -> turnedDown && notifyCandidate
                                        ? AfterCommit.run(() -> candidateEmails.notSelected(saved)).thenReturn(saved)
                                        : Mono.just(saved));
                    }));
                })
                .flatMap(this::toApplicationResponse);
    }

    /**
     * What a stage change means for the application's offers.
     *
     * <ul>
     *   <li>The offer stage is reached by sending an offer, so that a candidate
     *       at it always has one to answer.</li>
     *   <li>While a sent offer waits for the candidate's answer, only that
     *       answer hires them: HR marking them hired would accept it for them.</li>
     *   <li>Turning a candidate down, their withdrawing, or hiring them closes
     *       any open offer, drafts included.</li>
     *   <li>Moving them back from the offer stage closes the offer they were sent.</li>
     * </ul>
     */
    private Mono<Void> offersFollow(Application application, ApplicationStatus status, LocalDateTime now) {
        ApplicationStatus previous = application.getStatus();
        if (status == ApplicationStatus.OFFER_EXTENDED) {
            return Mono.error(new BadRequestException(
                    "Send the candidate an offer from the Offers page to move them to the offer stage."));
        }
        if (status == ApplicationStatus.HIRED) {
            return offerRepository.existsAwaitingAnswer(application.getId(), now)
                    .flatMap(awaiting -> awaiting
                            ? Mono.<Void>error(new ConflictException(
                                    "This candidate has an offer waiting for their answer. Accepting it hires them; "
                                            + "to hire them another way, withdraw it first."))
                            : offerRepository.closeOpen(application.getId(), now).then());
        }
        if (status == ApplicationStatus.REJECTED || status == ApplicationStatus.WITHDRAWN) {
            return offerRepository.closeOpen(application.getId(), now).then();
        }
        if (previous == ApplicationStatus.OFFER_EXTENDED) {
            return offerRepository.closeSent(application.getId(), now).then();
        }
        return Mono.empty();
    }

    /**
     * The text an application is read against: the attached resume, and the
     * cover letter. When resume text is not kept (file.upload.store-extracted-text
     * off), the skills parsed from the resume stand in for it. A candidate HR
     * added by hand has a link, not a document, so only their note is read.
     *
     * <p>A resume whose text could not be read (the parser found too few words,
     * as in a scanned image) is recorded as unreadable, so the team is not told
     * its keywords are missing when nobody could look for them.
     */
    private Mono<JobAlignment.Text> applicationText(Long resumeDocumentId, Long applicantId, String coverLetter) {
        String note = coverLetter == null ? "" : coverLetter;
        if (resumeDocumentId == null || applicantId == null) {
            return Mono.just(new JobAlignment.Text(note, JobAlignment.Source.NOTE));
        }
        return resumeDocumentRepository.findByIdAndUserId(resumeDocumentId, applicantId)
                .map(document -> {
                    String resume = document.getExtractedText() != null && !document.getExtractedText().isBlank()
                            ? document.getExtractedText()
                            : parsedSkills(document);
                    return resume.isBlank()
                            ? new JobAlignment.Text(note, JobAlignment.Source.UNREADABLE_RESUME)
                            : new JobAlignment.Text(resume + "\n" + note, JobAlignment.Source.RESUME_AND_NOTE);
                })
                .defaultIfEmpty(new JobAlignment.Text(note, JobAlignment.Source.UNREADABLE_RESUME));
    }

    /** The skills parsed from a resume, as the JSON list they are kept in, or blank when there are none. */
    private static String parsedSkills(CandidateResumeDocument document) {
        if (document.getParsedSkills() == null) return "";
        String skills = document.getParsedSkills().asString();
        return skills == null || skills.replaceAll("[\\[\\]\\s\"]", "").isEmpty() ? "" : skills;
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
                                    .alignmentSource(application.getAlignmentSource())
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
