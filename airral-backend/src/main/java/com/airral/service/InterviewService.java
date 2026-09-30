package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.User;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.InterviewFeedbackRequest;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.dto.response.InterviewResponse;
import com.airral.dto.response.InterviewerSummary;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class InterviewService {

    /** Who can be on an interview: the company's hiring team. */
    static final Set<UserRole> INTERVIEWER_ROLES = EnumSet.of(UserRole.HR_MANAGER, UserRole.MANAGER, UserRole.EMPLOYEE);
    static final int DEFAULT_DURATION_MINUTES = 60;

    /** Stages a booked interview moves a candidate forward from. */
    static final Set<ApplicationStatus> BEFORE_OFFER = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW, ApplicationStatus.SHORTLISTED,
            ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEWED);

    /** Stages feedback on an interview moves a candidate forward from. */
    static final Set<ApplicationStatus> BEFORE_INTERVIEWED = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW, ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEW_SCHEDULED);

    private final InterviewRepository interviewRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final CandidateUpdateEmails candidateEmails;
    private final InterviewerEmails interviewerEmails;

    public InterviewService(InterviewRepository interviewRepository,
                          ApplicationRepository applicationRepository,
                          JobRepository jobRepository,
                          UserRepository userRepository,
                          CandidateUpdateEmails candidateEmails,
                          InterviewerEmails interviewerEmails) {
        this.interviewRepository = interviewRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.candidateEmails = candidateEmails;
        this.interviewerEmails = interviewerEmails;
    }

    /**
     * Schedule a new interview. When the caller asks, the candidate is emailed
     * the time and the interviewers get an invitation, each with a calendar file.
     */
        @Transactional
    public Mono<InterviewResponse> scheduleInterview(ScheduleInterviewRequest request,
                                                     Long organizationId, Long userId, JobScope scope) {
        List<Long> interviewerIds = request.getInterviewerIds() == null ? List.of()
                : request.getInterviewerIds().stream().filter(Objects::nonNull).distinct().toList();
        // Verify the application belongs to this organization, and to the caller's jobs
        return timeZoneOf(request.getTimeZone()).flatMap(timeZone -> applicationRepository
                .findByIdAndOrganizationId(request.getApplicationId(), organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(application -> OfferService.CLOSED.contains(application.getStatus())
                        ? Mono.<Application>error(new ConflictException("This candidate's application is "
                                + application.getStatus().name().toLowerCase()
                                + ". Move it back to a stage before booking an interview."))
                        : Mono.just(application))
                .flatMap(application -> checkInterviewers(interviewerIds, organizationId).thenReturn(application))
                .flatMap(application -> {
                    Interview interview = Interview.builder()
                            .applicationId(request.getApplicationId())
                            .scheduledById(userId)
                            .interviewDate(request.getInterviewDate())
                            .durationMinutes(request.getDurationMinutes() != null
                                    ? request.getDurationMinutes() : DEFAULT_DURATION_MINUTES)
                            .timeZone(timeZone.isEmpty() ? null : timeZone)
                            .status("SCHEDULED")
                            .notes(request.getNotes())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    // Booking moves a candidate forward to "interview scheduled", never
                    // back: one at the offer stage keeps their offer.
                    Mono<Application> staged = BEFORE_OFFER.contains(application.getStatus())
                            ? Mono.defer(() -> {
                                application.setStatus(ApplicationStatus.INTERVIEW_SCHEDULED);
                                application.setUpdatedAt(LocalDateTime.now());
                                return applicationRepository.save(application);
                            })
                            : Mono.just(application);

                    return staged
                            .then(interviewRepository.save(interview))
                            .flatMap(saved -> Flux.fromIterable(interviewerIds)
                                    .concatMap(interviewerId -> interviewRepository.addInterviewer(saved.getId(), interviewerId))
                                    .then(Mono.just(saved)))
                            .flatMap(saved -> AfterCommit.run(() -> {
                                if (Boolean.TRUE.equals(request.getNotifyCandidate())) {
                                    candidateEmails.interviewBooked(application, saved);
                                }
                                if (Boolean.TRUE.equals(request.getNotifyInterviewers())) {
                                    interviewerEmails.invite(saved, application, interviewerIds);
                                }
                            }).thenReturn(saved));
                }))
                .flatMap(this::toInterviewResponse);
    }

    /**
     * The interviews the caller is on as an interviewer, in their own company,
     * soonest first. Every role on the hiring team can be an interviewer, so
     * this is not limited to the jobs a hiring manager owns.
     */
    public Flux<InterviewResponse> getMyInterviews(Long userId, Long organizationId) {
        return interviewRepository.findByInterviewer(userId, organizationId)
                .concatMap(this::toInterviewResponse)
                .map(interview -> {
                    // HR's own feedback and rating, from before scorecards, are not the
                    // interviewers' to read: each writes their scorecard unswayed.
                    interview.setFeedback(null);
                    interview.setRating(null);
                    return interview;
                });
    }

    /** Interviewers must be active members of the company's hiring team. */
    private Mono<Void> checkInterviewers(List<Long> interviewerIds, Long organizationId) {
        return Flux.fromIterable(interviewerIds)
                .concatMap(interviewerId -> userRepository.findById(interviewerId)
                        .filter(user -> organizationId != null
                                && organizationId.equals(user.getOrganizationId())
                                && Boolean.TRUE.equals(user.getIsActive())
                                && INTERVIEWER_ROLES.contains(user.getRole()))
                        .switchIfEmpty(Mono.error(new BadRequestException(
                                "Choose interviewers from your company's team"))))
                .then();
    }

    /** The booking's time zone, checked. Empty when none was given. */
    private static Mono<String> timeZoneOf(String requested) {
        if (requested == null || requested.isBlank()) return Mono.just("");
        try {
            return Mono.just(ZoneId.of(requested.trim()).getId());
        } catch (DateTimeException e) {
            return Mono.error(new BadRequestException("Unknown time zone: " + requested));
        }
    }

    /**
     * Get all interviews for an organization
     */
    public Flux<InterviewResponse> getAllInterviews(Long organizationId, JobScope scope) {
        return interviewRepository.findAllByOrganizationId(organizationId)
                .filterWhen(interview -> inScope(interview, scope))
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get interviews by application
     */
    public Flux<InterviewResponse> getInterviewsByApplication(Long applicationId, Long organizationId, JobScope scope) {
        // First verify the application belongs to this organization, and to the caller's jobs
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .flatMapMany(app -> interviewRepository.findByApplicationId(applicationId))
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get upcoming interviews
     */
    public Flux<InterviewResponse> getUpcomingInterviews(Long organizationId, JobScope scope) {
        return interviewRepository.findUpcomingByOrganizationId(organizationId, LocalDateTime.now())
                .filterWhen(interview -> inScope(interview, scope))
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get interviews by date range (for calendar view)
     */
    public Flux<InterviewResponse> getInterviewsByDateRange(Long organizationId,
                                                            LocalDateTime startDate,
                                                            LocalDateTime endDate,
                                                            JobScope scope) {
        return interviewRepository.findByOrganizationIdAndDateRange(organizationId, startDate, endDate)
                .filterWhen(interview -> inScope(interview, scope))
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Submit interview feedback
     */
        @Transactional
    public Mono<InterviewResponse> submitFeedback(Long interviewId, InterviewFeedbackRequest request,
                                                  Long organizationId, JobScope scope) {
        return interviewRepository.findByIdAndOrganizationId(interviewId, organizationId)
                .filterWhen(interview -> inScope(interview, scope))
                .switchIfEmpty(Mono.error(new NotFoundException("Interview not found")))
                .flatMap(interview -> {
                    interview.setFeedback(request.getFeedback());
                    interview.setRating(request.getRating());
                    // Feedback without notes keeps the notes from booking.
                    if (request.getNotes() != null) {
                        interview.setNotes(request.getNotes());
                    }
                    interview.setStatus("COMPLETED");
                    interview.setUpdatedAt(LocalDateTime.now());

                    // Forward to "interviewed", never back: feedback written after an
                    // offer went out, or after a decision, leaves the stage alone.
                    return applicationRepository.findById(interview.getApplicationId())
                            .filter(application -> BEFORE_INTERVIEWED.contains(application.getStatus()))
                            .flatMap(application -> {
                                application.setStatus(ApplicationStatus.INTERVIEWED);
                                application.setUpdatedAt(LocalDateTime.now());
                                return applicationRepository.save(application);
                            })
                            .then(interviewRepository.save(interview));
                })
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Convert Interview entity to InterviewResponse DTO
     */
    private Mono<InterviewResponse> toInterviewResponse(Interview interview) {
        return applicationRepository.findById(interview.getApplicationId())
                .flatMap(application -> 
                    jobRepository.findById(application.getJobId())
                            .flatMap(job -> {
                                // An interview whose booker was removed keeps no booker.
                                Mono<String> scheduledByMono = interview.getScheduledById() == null
                                        ? Mono.just("Unknown")
                                        : userRepository.findById(interview.getScheduledById())
                                                .map(InterviewService::displayName)
                                                .defaultIfEmpty("Unknown");

                                return Mono.zip(scheduledByMono, interviewersOf(interview)).map(found ->
                                        InterviewResponse.builder()
                                                .id(interview.getId())
                                                .applicationId(interview.getApplicationId())
                                                .jobId(application.getJobId())
                                                .candidateName(application.getApplicantName())
                                                .candidateEmail(application.getApplicantEmail())
                                                .jobTitle(job.getTitle())
                                                .scheduledBy(found.getT1())
                                                .interviewDate(interview.getInterviewDate())
                                                .durationMinutes(interview.getDurationMinutes())
                                                .timeZone(interview.getTimeZone())
                                                .interviewers(found.getT2())
                                                .status(interview.getStatus())
                                                .feedback(interview.getFeedback())
                                                .rating(interview.getRating())
                                                .notes(interview.getNotes())
                                                .createdAt(interview.getCreatedAt())
                                                .updatedAt(interview.getUpdatedAt())
                                                .build()
                                );
                            })
                );
    }

    private Mono<List<InterviewerSummary>> interviewersOf(Interview interview) {
        if (interview.getId() == null) return Mono.just(List.of());
        return interviewRepository.findInterviewerIds(interview.getId())
                .concatMap(userRepository::findById)
                .map(user -> InterviewerSummary.builder().id(user.getId()).name(displayName(user)).build())
                .collectList();
    }

    /** A teammate's name, or their email when they have not given one. */
    static String displayName(User user) {
        String name = Stream.of(user.getFirstName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? user.getEmail() : name;
    }

    /** An interview belongs to the caller's jobs when its application does. */
    private Mono<Boolean> inScope(Interview interview, JobScope scope) {
        if (scope.isWholeCompany()) {
            return Mono.just(true);
        }
        return applicationRepository.findById(interview.getApplicationId())
                .map(application -> scope.allows(application.getJobId()))
                .defaultIfEmpty(false);
    }
}
