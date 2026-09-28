package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.User;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.InterviewFeedbackRequest;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.exception.BadRequestException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who can be on an interview, and what an interviewer sees.
 */
class InterviewTeamTest {

    private static final long ACME = 1L;
    private static final long OTHER_COMPANY = 2L;
    private static final long HANA = 3L;     // HR manager who books
    private static final long IVAN = 4L;     // interviewer at Acme
    private static final long MIA = 5L;      // hiring manager at Acme
    private static final long OSCAR = 6L;    // interviewer at another company
    private static final long QUINN = 7L;    // Acme interviewer who was switched off
    private static final long AMY = 8L;      // an applicant

    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InterviewerEmails interviewerEmails = mock(InterviewerEmails.class);
    private final InterviewService service =
            new InterviewService(interviews, applications, jobs, users, mock(CandidateUpdateEmails.class), interviewerEmails);

    private final Application amysApplication = Application.builder().id(100L).jobId(10L)
            .applicantId(AMY).applicantName("Amy Adams").applicantEmail("amy@example.com")
            .status(ApplicationStatus.SHORTLISTED).build();

    @BeforeEach
    void setUp() {
        person(HANA, ACME, UserRole.HR_MANAGER, true, "Hana", "Hill");
        person(IVAN, ACME, UserRole.EMPLOYEE, true, "Ivan", null);
        person(MIA, ACME, UserRole.MANAGER, true, null, null);
        person(OSCAR, OTHER_COMPANY, UserRole.EMPLOYEE, true, "Oscar", "Other");
        person(QUINN, ACME, UserRole.EMPLOYEE, false, "Quinn", "Quiet");
        person(AMY, null, UserRole.APPLICANT, true, "Amy", "Adams");
        when(users.findById(999L)).thenReturn(Mono.empty());

        when(applications.findByIdAndOrganizationId(100L, ACME)).thenReturn(Mono.just(amysApplication));
        when(applications.findById(100L)).thenReturn(Mono.just(amysApplication));
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jobs.findById(10L)).thenReturn(Mono.just(Job.builder().id(10L).organizationId(ACME).title("Backend engineer").build()));
        when(interviews.save(any(Interview.class))).thenAnswer(inv -> {
            Interview interview = inv.getArgument(0);
            if (interview.getId() == null) interview.setId(900L);
            return Mono.just(interview);
        });
        when(interviews.addInterviewer(anyLong(), anyLong())).thenReturn(Mono.just(1));
        when(interviews.findInterviewerIds(900L)).thenReturn(Flux.just(IVAN, MIA));
    }

    private void person(long id, Long companyId, UserRole role, boolean active, String first, String last) {
        when(users.findById(id)).thenReturn(Mono.just(User.builder().id(id).organizationId(companyId).role(role)
                .isActive(active).firstName(first).lastName(last).email("user" + id + "@example.com").build()));
    }

    private static ScheduleInterviewRequest booking(Long... interviewerIds) {
        ScheduleInterviewRequest request = new ScheduleInterviewRequest();
        request.setApplicationId(100L);
        request.setInterviewDate(LocalDateTime.of(2026, 10, 1, 14, 0));
        request.setInterviewerIds(List.of(interviewerIds));
        return request;
    }

    @Test
    @DisplayName("HR puts teammates on an interview; it keeps its length and the booker's time zone")
    void bookTeammates() {
        ScheduleInterviewRequest request = booking(IVAN, MIA, IVAN);
        request.setTimeZone("America/New_York");

        StepVerifier.create(service.scheduleInterview(request, ACME, HANA, JobScope.wholeCompany()))
                .assertNext(response -> {
                    assertThat(response.getDurationMinutes()).isEqualTo(60);
                    assertThat(response.getTimeZone()).isEqualTo("America/New_York");
                    assertThat(response.getJobId()).isEqualTo(10L);
                    // A teammate with no name shows as their email.
                    assertThat(response.getInterviewers()).extracting("name")
                            .containsExactly("Ivan", "user5@example.com");
                })
                .verifyComplete();

        // Ivan was listed twice and is added once.
        verify(interviews).addInterviewer(900L, IVAN);
        verify(interviews).addInterviewer(900L, MIA);
    }

    @Test
    @DisplayName("interviewers are invited only when the booker asks")
    void invitesAreOptIn() {
        service.scheduleInterview(booking(IVAN, MIA), ACME, HANA, JobScope.wholeCompany()).block();
        verify(interviewerEmails, never()).invite(any(), any(), any());

        ScheduleInterviewRequest request = booking(IVAN, MIA);
        request.setNotifyInterviewers(true);
        service.scheduleInterview(request, ACME, HANA, JobScope.wholeCompany()).block();
        verify(interviewerEmails).invite(any(Interview.class), eq(amysApplication), eq(List.of(IVAN, MIA)));
    }

    @Test
    @DisplayName("an interviewer must be an active member of the company's hiring team")
    void onlyTheCompanysTeam() {
        for (long outsider : new long[] {OSCAR, QUINN, AMY, 999L}) {
            StepVerifier.create(service.scheduleInterview(booking(IVAN, outsider), ACME, HANA, JobScope.wholeCompany()))
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isInstanceOf(BadRequestException.class)
                            .hasMessageContaining("team"))
                    .verify();
        }
        verify(interviews, never()).save(any());
        verify(interviews, never()).addInterviewer(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a time zone that does not exist is refused")
    void unknownTimeZone() {
        ScheduleInterviewRequest request = booking(IVAN);
        request.setTimeZone("Mars/Olympus");

        StepVerifier.create(service.scheduleInterview(request, ACME, HANA, JobScope.wholeCompany()))
                .expectError(BadRequestException.class)
                .verify();
        verify(interviews, never()).save(any());
    }

    @Test
    @DisplayName("an interviewer sees the interviews they are on, in their own company")
    void myInterviews() {
        Interview booked = Interview.builder().id(900L).applicationId(100L).scheduledById(HANA)
                .interviewDate(LocalDateTime.of(2026, 10, 1, 14, 0)).status("SCHEDULED").build();
        when(interviews.findByInterviewer(IVAN, ACME)).thenReturn(Flux.just(booked));

        StepVerifier.create(service.getMyInterviews(IVAN, ACME))
                .assertNext(response -> {
                    assertThat(response.getCandidateName()).isEqualTo("Amy Adams");
                    assertThat(response.getJobTitle()).isEqualTo("Backend engineer");
                    assertThat(response.getScheduledBy()).isEqualTo("Hana Hill");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an interview whose booker was removed still loads")
    void bookerRemoved() {
        Interview orphan = Interview.builder().id(900L).applicationId(100L).status("SCHEDULED").build();
        when(interviews.findByInterviewer(IVAN, ACME)).thenReturn(Flux.just(orphan));

        StepVerifier.create(service.getMyInterviews(IVAN, ACME))
                .assertNext(response -> assertThat(response.getScheduledBy()).isEqualTo("Unknown"))
                .verifyComplete();
    }

    @Test
    @DisplayName("feedback without notes keeps the notes written when the interview was booked")
    void feedbackKeepsBookingNotes() {
        Interview booked = Interview.builder().id(900L).applicationId(100L).scheduledById(HANA)
                .notes("Panel: ask about the outage").status("SCHEDULED").build();
        when(interviews.findByIdAndOrganizationId(900L, ACME)).thenReturn(Mono.just(booked));
        InterviewFeedbackRequest feedback = new InterviewFeedbackRequest();
        feedback.setFeedback("Strong on incident response");
        feedback.setRating(4);

        service.submitFeedback(900L, feedback, ACME, JobScope.wholeCompany()).block();

        ArgumentCaptor<Interview> saved = ArgumentCaptor.forClass(Interview.class);
        verify(interviews).save(saved.capture());
        assertThat(saved.getValue().getNotes()).isEqualTo("Panel: ask about the outage");
        assertThat(saved.getValue().getFeedback()).isEqualTo("Strong on incident response");
    }
}
