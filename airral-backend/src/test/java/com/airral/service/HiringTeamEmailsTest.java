package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.Offer;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.OfferStatus;
import com.airral.domain.enums.UserRole;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who on the hiring team hears that a candidate answered an offer. */
class HiringTeamEmailsTest {

    private static final long ACME = 1L;

    private final CandidateEmailService email = mock(CandidateEmailService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final HiringTeamEmails emails;

    private final Job job = Job.builder().id(10L).organizationId(ACME).title("Buyer").hiringManagerId(20L).build();
    private final Application application = Application.builder().id(100L).jobId(10L).applicantName("Amy Adams").build();
    private final Offer accepted = Offer.builder().id(500L).applicationId(100L).status(OfferStatus.ACCEPTED).build();

    HiringTeamEmailsTest() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));
        emails = new HiringTeamEmails(email, jobs, organizations, users, "https://app.airral.com");
    }

    @BeforeEach
    void setUp() {
        when(jobs.findById(10L)).thenReturn(Mono.just(job));
        when(users.findActiveHrManagers(ACME)).thenReturn(Flux.just(
                User.builder().id(3L).organizationId(ACME).role(UserRole.HR_MANAGER).isActive(true).email("hana@acme.io").build()));
        when(email.wrapTransactional(anyString(), anyString(), anyString())).thenReturn("<html/>");
        when(email.sendEmail(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    private void hiringManager(UserRole role, long company, boolean active) {
        when(users.findById(20L)).thenReturn(Mono.just(User.builder().id(20L).organizationId(company).role(role)
                .isActive(active).email("mia@acme.io").build()));
    }

    @Test
    @DisplayName("the job's hiring manager hears, with HR")
    void hiringManagerHears() {
        hiringManager(UserRole.MANAGER, ACME, true);

        emails.offerAnswered(accepted, application);

        verify(email, timeout(2000)).sendEmail(eq("hana@acme.io"), anyString(), anyString());
        verify(email, timeout(2000)).sendEmail(eq("mia@acme.io"), anyString(), anyString());
    }

    @Test
    @DisplayName("a hiring manager named on the job before they left hiring, or the company, does not")
    void formerHiringManagerDoesNot() {
        for (Runnable former : new Runnable[] {
                () -> hiringManager(UserRole.EMPLOYEE, ACME, true),
                () -> hiringManager(UserRole.MANAGER, 2L, true),
                () -> hiringManager(UserRole.MANAGER, ACME, false)}) {
            former.run();
            emails.offerAnswered(accepted, application);
        }

        verify(email, timeout(2000).times(3)).sendEmail(eq("hana@acme.io"), anyString(), anyString());
        verify(email, never()).sendEmail(eq("mia@acme.io"), any(), any());
    }
}
