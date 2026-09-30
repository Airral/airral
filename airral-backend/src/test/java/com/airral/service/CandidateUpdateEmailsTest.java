package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.repository.CandidateNotificationPreferenceRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What candidates are told about their applications, and when AIRRAL stays
 * quiet.
 *
 * <p>The company's name and job title are typed by the company, so every test
 * below uses ones with markup in them.
 */
class CandidateUpdateEmailsTest {

    private static final long JOB = 10L;
    private static final long COMPANY = 1L;

    private final CandidateEmailService email = mock(CandidateEmailService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final CandidateUpdateEmails emails =
            new CandidateUpdateEmails(email, jobs, organizations, "https://apply.airral.com", "notifications@airral.com");

    @BeforeEach
    void setUp() {
        when(email.sendEmail(any(), any(), any(), any())).thenReturn(Mono.empty());
        // The template is tested on its own below; here it shows what went into it.
        when(email.wrapTransactional(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1) + "|footer: " + inv.getArgument(2));
        jobTitled("<b>Backend</b> engineer");
    }

    private void jobTitled(String title) {
        when(jobs.findById(JOB)).thenReturn(Mono.just(
                Job.builder().id(JOB).organizationId(COMPANY).title(title).build()));
    }

    private void companyIs(String verificationStatus, String timezone) {
        when(organizations.findById(COMPANY)).thenReturn(Mono.just(Organization.builder()
                .id(COMPANY).name("Acme & Co").isActive(true)
                .verificationStatus(verificationStatus).timezone(timezone).build()));
    }

    private static Application amy() {
        return Application.builder().id(55L).jobId(JOB).applicantId(7L)
                .applicantName("Amy Adams").applicantEmail("amy@example.com").build();
    }

    /** Someone HR added by hand: no AIRRAL account. */
    private static Application hal() {
        return Application.builder().id(56L).jobId(JOB)
                .applicantName("Hal Hughes").applicantEmail("hal@example.com").build();
    }

    /** Subject, body and calendar file of the one email sent to this address. */
    private String[] sentTo(String address) {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> calendar = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq(address), subject.capture(), body.capture(), calendar.capture());
        return new String[] {subject.getValue(), body.getValue(), calendar.getValue()};
    }

    @Test
    @DisplayName("an applicant hears that the company has their application")
    void applicationReceived() {
        companyIs(CompanyVerificationService.VERIFIED, null);

        emails.applicationReceived(amy());

        String[] sent = sentTo("amy@example.com");
        assertThat(sent[0]).isEqualTo("We sent your application to Acme & Co");
        assertThat(sent[1])
                .contains("Hi Amy,")
                .contains("Acme &amp; Co has your application for <strong>&lt;b&gt;Backend&lt;/b&gt; engineer</strong>")
                .contains("https://apply.airral.com/tracker")
                .contains("|footer: you applied to this job on AIRRAL")
                .doesNotContain("<b>Backend</b>");
    }

    @Test
    @DisplayName("a company still waiting for review emails no one")
    void unverifiedCompanyStaysQuiet() {
        companyIs("PENDING", null);
        Interview interview = Interview.builder().interviewDate(LocalDateTime.of(2026, 10, 1, 14, 0)).build();

        emails.applicationReceived(amy());
        emails.interviewBooked(hal(), interview);
        emails.notSelected(hal());

        verify(email, never()).sendEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the interview email gives the day and time in the zone it was booked in, with a calendar file")
    void interviewBooked() {
        companyIs(CompanyVerificationService.VERIFIED, "Europe/London");
        Interview interview = Interview.builder().id(900L).interviewDate(LocalDateTime.of(2026, 10, 1, 14, 0))
                .durationMinutes(45).timeZone("America/New_York").notes("Panel: ask about the outage").build();

        emails.interviewBooked(hal(), interview);

        String[] sent = sentTo("hal@example.com");
        assertThat(sent[0]).isEqualTo("Interview with Acme & Co: <b>Backend</b> engineer");
        assertThat(sent[1])
                .contains("Hi Hal,")
                // The booker's zone, not the company's.
                .contains("<strong>Thursday 1 October at 2:00 PM EDT</strong> (45 minutes)")
                .contains("The attached file adds it to your calendar.")
                // The panel's notes are the team's, not the candidate's.
                .doesNotContain("outage")
                .contains("|footer: a company that hires with AIRRAL is considering you for this job");
        assertThat(sent[2])
                .contains("DTSTART:20261001T180000Z")
                .contains("DTEND:20261001T184500Z")
                .contains("mailto:hal@example.com")
                .doesNotContain("outage");
    }

    @Test
    @DisplayName("without a zone it knows, the time is the company's local time and there is no calendar file")
    void interviewTimeWithoutAZone() {
        companyIs(CompanyVerificationService.VERIFIED, "Mars/Olympus");
        Interview interview = Interview.builder().id(900L).interviewDate(LocalDateTime.of(2026, 10, 1, 14, 0)).build();

        assertThat(CandidateUpdateEmails.interviewTime(interview, Organization.builder().name("Acme").build()))
                .isEqualTo("Thursday 1 October at 2:00 PM, Acme's local time");

        emails.interviewBooked(hal(), interview);
        String[] sent = sentTo("hal@example.com");
        assertThat(sent[1]).contains("2:00 PM, Acme &amp; Co").contains("local time").doesNotContain("attached file");
        assertThat(sent[2]).isNull();
    }

    @Test
    @DisplayName("turning down an applicant points them at more jobs; someone HR added gets no AIRRAL link")
    void notSelected() {
        companyIs(CompanyVerificationService.VERIFIED, null);

        emails.notSelected(amy());
        emails.notSelected(hal());

        assertThat(sentTo("amy@example.com")[1]).contains("decided not to move forward").contains("Find more jobs");
        assertThat(sentTo("hal@example.com")[1]).contains("decided not to move forward").doesNotContain("Find more jobs");
    }

    @Test
    @DisplayName("a job title cannot add lines to the email's headers")
    void subjectStaysOneLine() {
        companyIs(CompanyVerificationService.VERIFIED, null);
        jobTitled("Engineer\r\nBcc: everyone@example.com");

        emails.interviewBooked(amy(), Interview.builder().build());

        assertThat(sentTo("amy@example.com")[0]).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    @DisplayName("a failed send never reaches the action that caused it")
    void failureIsContained() {
        companyIs(CompanyVerificationService.VERIFIED, null);
        when(email.sendEmail(any(), any(), any(), any())).thenReturn(Mono.error(new MailSendException("SMTP is down")));

        assertThatCode(() -> emails.applicationReceived(amy())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the template escapes what it is given and has no unsubscribe link")
    void transactionalTemplate() {
        CandidateEmailService real = new CandidateEmailService(mock(JavaMailSender.class),
                mock(CandidateNotificationPreferenceRepository.class), mock(UserRepository.class),
                "notifications@airral.com", "AIRRAL", "https://apply.airral.com", false, "");

        String html = real.wrapTransactional("Interview with <Acme>", "<p>Body</p>", "you & we");

        assertThat(html)
                .contains("<title>Interview with &lt;Acme&gt;</title>")
                .contains("<p>Body</p>")
                .contains("You're getting this because you &amp; we.")
                .doesNotContain("nsubscribe");
    }
}
