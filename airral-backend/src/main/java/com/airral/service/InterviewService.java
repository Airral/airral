package com.airral.service;

import com.airral.domain.Interview;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.dto.request.InterviewFeedbackRequest;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.dto.response.InterviewResponse;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class InterviewService {

    private final InterviewRepository interviewRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public InterviewService(InterviewRepository interviewRepository,
                          ApplicationRepository applicationRepository,
                          JobRepository jobRepository,
                          UserRepository userRepository) {
        this.interviewRepository = interviewRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    /**
     * Schedule a new interview
     */
        @Transactional
    public Mono<InterviewResponse> scheduleInterview(ScheduleInterviewRequest request, 
                                                     Long organizationId, Long userId) {
        // Verify the application belongs to this organization
        return applicationRepository.findByIdAndOrganizationId(request.getApplicationId(), organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(application -> {
                    Interview interview = Interview.builder()
                            .applicationId(request.getApplicationId())
                            .scheduledById(userId)
                            .interviewDate(request.getInterviewDate())
                            .status("SCHEDULED")
                            .notes(request.getNotes())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    // Update application status
                    application.setStatus(ApplicationStatus.INTERVIEW_SCHEDULED);
                    application.setUpdatedAt(LocalDateTime.now());

                    return applicationRepository.save(application)
                            .then(interviewRepository.save(interview));
                })
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get all interviews for an organization
     */
    public Flux<InterviewResponse> getAllInterviews(Long organizationId) {
        return interviewRepository.findAllByOrganizationId(organizationId)
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get interviews by application
     */
    public Flux<InterviewResponse> getInterviewsByApplication(Long applicationId, Long organizationId) {
        // First verify the application belongs to this organization
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .flatMapMany(app -> interviewRepository.findByApplicationId(applicationId))
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get upcoming interviews
     */
    public Flux<InterviewResponse> getUpcomingInterviews(Long organizationId) {
        return interviewRepository.findUpcomingByOrganizationId(organizationId, LocalDateTime.now())
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Get interviews by date range (for calendar view)
     */
    public Flux<InterviewResponse> getInterviewsByDateRange(Long organizationId, 
                                                            LocalDateTime startDate, 
                                                            LocalDateTime endDate) {
        return interviewRepository.findByOrganizationIdAndDateRange(organizationId, startDate, endDate)
                .flatMap(this::toInterviewResponse);
    }

    /**
     * Submit interview feedback
     */
        @Transactional
    public Mono<InterviewResponse> submitFeedback(Long interviewId, InterviewFeedbackRequest request, 
                                                  Long organizationId) {
        return interviewRepository.findByIdAndOrganizationId(interviewId, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Interview not found")))
                .flatMap(interview -> {
                    interview.setFeedback(request.getFeedback());
                    interview.setRating(request.getRating());
                    interview.setNotes(request.getNotes());
                    interview.setStatus("COMPLETED");
                    interview.setUpdatedAt(LocalDateTime.now());

                    // Update application status to INTERVIEWED
                    return applicationRepository.findById(interview.getApplicationId())
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
                                Mono<String> scheduledByMono = userRepository.findById(interview.getScheduledById())
                                        .map(user -> user.getFullName())
                                        .defaultIfEmpty("Unknown");

                                return scheduledByMono.map(scheduledBy ->
                                        InterviewResponse.builder()
                                                .id(interview.getId())
                                                .applicationId(interview.getApplicationId())
                                                .candidateName(application.getApplicantName())
                                                .candidateEmail(application.getApplicantEmail())
                                                .jobTitle(job.getTitle())
                                                .scheduledBy(scheduledBy)
                                                .interviewDate(interview.getInterviewDate())
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
}
