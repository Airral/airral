package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewerEmailsTest {

    private final CandidateEmailService email = mock(CandidateEmailService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InterviewerEmails invites = new InterviewerEmails(email, jobs, organizations, users,
            "https://app.airral.com", "notifications@airral.com");

    private final Interview interview = Interview.builder().id(900L).applicationId(100L)
            .interviewDate(LocalDateTime.of(2026, 10, 1, 14, 0)).durationMinutes(45).timeZone("America/Chicago")
            .notes("Ask about <the> Saturday rush").build();
    private final Application application = Application.builder().id(100L).jobId(10L)
            .applicantName("Amy Adams").applicantEmail("amy@example.com").build();

    @BeforeEach
    void setUp() {
        when(jobs.findById(10L)).thenReturn(Mono.just(Job.builder().id(10L).organizationId(1L).title("Store manager").build()));
        // Still waiting for review: teammates are invited all the same.
        when(organizations.findById(1L)).thenReturn(Mono.just(Organization.builder().id(1L).name("Acme")
                .verificationStatus("PENDING").build()));
        when(users.findById(4L)).thenReturn(Mono.just(User.builder().id(4L).firstName("Ivan").email("ivan@acme.test").build()));
        when(users.findById(5L)).thenReturn(Mono.just(User.builder().id(5L).firstName("Mia").lastName("Moss").email("mia@acme.test").build()));
        when(email.wrapTransactional(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(email.sendEmail(any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("each interviewer gets the time, the team's notes, their scorecard and a calendar file")
    void invitesEachInterviewer() {
        invites.invite(interview, application, List.of(4L, 5L));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> calendar = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq("ivan@acme.test"), eq("Interview: Amy Adams for Store manager"), body.capture(), calendar.capture());
        assertThat(body.getValue())
                .contains("Hi Ivan,")
                .contains("Thursday 1 October at 2:00 PM CDT")
                .contains("With Mia Moss.")
                .contains("Ask about &lt;the&gt; Saturday rush")
                .contains("https://app.airral.com/interviews/scorecard?interviewId=900");
        assertThat(calendar.getValue())
                .contains("DTSTART:20261001T190000Z")
                .contains("mailto:ivan@acme.test")
                .contains("mailto:mia@acme.test");
        verify(email).sendEmail(eq("mia@acme.test"), any(), any(), any());
    }

    @Test
    @DisplayName("one address failing does not stop the others")
    void oneFailureOnly() {
        when(email.sendEmail(eq("ivan@acme.test"), any(), any(), any())).thenReturn(Mono.error(new MailSendException("bounced")));

        invites.invite(interview, application, List.of(4L, 5L));

        verify(email).sendEmail(eq("mia@acme.test"), any(), any(), any());
    }
}
