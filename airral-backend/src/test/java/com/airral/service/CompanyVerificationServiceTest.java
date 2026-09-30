package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.JobStatus;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When proving an email address is also evidence of working at a company.
 *
 * <p>Proving an inbox is not proving an employer. These cases pin the one rule
 * that lets the first stand in for the second -- an address on the company's own,
 * non-free-mail domain -- and every way that rule must not fire.
 */
class CompanyVerificationServiceTest {

    private OrganizationRepository organizationRepository;
    private JobRepository jobRepository;
    private InternalJobCatalogProjectionService projection;
    private final TeamAlerts alerts = mock(TeamAlerts.class);
    private final UserService users = mock(UserService.class);
    private CompanyVerificationService service;

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        jobRepository = mock(JobRepository.class);
        projection = mock(InternalJobCatalogProjectionService.class);
        // The domain tests below describe approval by work email, which is
        // switched on here; companyWaitsForReviewWhileDomainApprovalIsOff covers
        // the default.
        service = new CompanyVerificationService(organizationRepository, jobRepository, projection, alerts, users, true, 7);
        when(users.sendHeldInvitations(any())).thenReturn(Mono.just(0L));
        when(organizationRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jobRepository.findByOrganizationIdAndStatus(any(), any())).thenReturn(Flux.empty());
        when(organizationRepository.existsVerifiedDomainOtherThan(any(), any())).thenReturn(Mono.just(false));
    }

    @Test
    @DisplayName("with approval by work email off, proving a work address leaves the company for review")
    void companyWaitsForReviewWhileDomainApprovalIsOff() {
        CompanyVerificationService reviewEveryCompany =
                new CompanyVerificationService(organizationRepository, jobRepository, projection, alerts, users, false, 7);
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(company("stripe.com")));

        StepVerifier.create(reviewEveryCompany.onEmailProven(hr("bob@stripe.com"))).verifyComplete();

        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a second company proving a domain another already holds is left for review")
    void duplicateDomainIsLeftForReview() {
        Organization org = company("stripe.com");
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(org));
        when(organizationRepository.existsVerifiedDomainOtherThan("stripe.com", 9L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.onEmailProven(hr("alice@stripe.com"))).verifyComplete();

        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("the company domain comes from the signup address, and free mail gives none")
    void companyDomainIsDerivedNotTyped() {
        assertThat(CompanyVerificationService.companyDomainFor("Bob@Stripe.com")).isEqualTo("stripe.com");
        assertThat(CompanyVerificationService.companyDomainFor("bob@gmail.com")).isNull();
        assertThat(CompanyVerificationService.companyDomainFor("bob@outlook.com")).isNull();
        assertThat(CompanyVerificationService.companyDomainFor("not-an-email")).isNull();
    }

    @Test
    @DisplayName("proving an address on the company's own domain verifies the company")
    void verifiesByDomain() {
        Organization org = company("stripe.com");
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(org));

        StepVerifier.create(service.onEmailProven(hr("bob@stripe.com"))).verifyComplete();

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(saved.capture());
        assertThat(saved.getValue().getVerificationStatus()).isEqualTo("VERIFIED");
        assertThat(saved.getValue().getVerificationMethod()).isEqualTo("DOMAIN");
        // ...and its open jobs are re-projected now, not at the next reconcile.
        verify(jobRepository).findByOrganizationIdAndStatus(9L, JobStatus.OPEN);
    }

    @Test
    @DisplayName("a proven free-mail address leaves the company waiting for review")
    void freeMailDoesNotVerify() {
        // bob@gmail.com calling himself Stripe has proven an inbox and nothing else.
        Organization org = company(null);
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(org));

        StepVerifier.create(service.onEmailProven(hr("bob@gmail.com"))).verifyComplete();

        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("an address on a different domain does not verify the company")
    void mismatchedDomainDoesNotVerify() {
        Organization org = company("stripe.com");
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(org));

        StepVerifier.create(service.onEmailProven(hr("bob@stripe-careers.net"))).verifyComplete();

        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a company an admin rejected is not re-verified by a later email proof")
    void rejectedStaysRejected() {
        Organization org = company("stripe.com");
        org.setVerificationStatus("REJECTED");
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(org));

        StepVerifier.create(service.onEmailProven(hr("bob@stripe.com"))).verifyComplete();

        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("an applicant proving an address touches no company")
    void applicantsHaveNoCompany() {
        User applicant = User.builder().id(3L).email("amy@gmail.com").organizationId(null).build();
        StepVerifier.create(service.onEmailProven(applicant)).verifyComplete();
        verify(organizationRepository, never()).findById(any(Long.class));
    }

    private Organization company(String domain) {
        return Organization.builder().id(9L).name("Stripe").domain(domain)
                .isActive(true).verificationStatus("PENDING").build();
    }

    private User hr(String email) {
        return User.builder().id(5L).email(email).organizationId(9L).build();
    }

    @Test
    @DisplayName("a company is announced to the team while it waits for review, not once it is approved")
    void announcesOnlyWaitingCompanies() {
        Organization waiting = company("acme.io");
        waiting.setVerificationStatus(CompanyVerificationService.PENDING);
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(waiting));
        StepVerifier.create(service.announceNewCompany(hr("amy@acme.io"))).verifyComplete();
        verify(alerts).newCompany(eq(waiting), any());

        waiting.setVerificationStatus(CompanyVerificationService.VERIFIED);
        StepVerifier.create(service.announceNewCompany(hr("amy@acme.io"))).verifyComplete();
        verify(alerts, org.mockito.Mockito.times(1)).newCompany(any(), any());
    }

    @Test
    @DisplayName("approving a company sends the invitations it made while waiting; rejecting does not")
    void approvalSendsHeldInvitations() {
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(company("acme.io")));

        StepVerifier.create(service.approve(9L, "Checked")).expectNextCount(1).verifyComplete();
        verify(users).sendHeldInvitations(9L);

        StepVerifier.create(service.reject(9L, "Not a company")).expectNextCount(1).verifyComplete();
        verify(users, org.mockito.Mockito.times(1)).sendHeldInvitations(any());
    }

    @Test
    @DisplayName("approval stands even when the held invitations cannot go out")
    void approvalSurvivesAFailedSend() {
        when(organizationRepository.findById(9L)).thenReturn(Mono.just(company("acme.io")));
        when(users.sendHeldInvitations(9L)).thenReturn(Mono.error(new IllegalStateException("Firebase is down")));

        StepVerifier.create(service.approve(9L, "Checked"))
                .assertNext(org -> assertThat(org.getVerificationStatus()).isEqualTo(CompanyVerificationService.VERIFIED))
                .verifyComplete();
    }

    @Test
    @DisplayName("sign-ups nobody verified close after the configured days, with a note saying why")
    void closesUnverifiedSignups() {
        when(organizationRepository.closeUnverifiedSignups(any(), any(), any())).thenReturn(Mono.just(2L));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);

        StepVerifier.create(service.closeUnverifiedBefore(cutoff)).expectNext(2L).verifyComplete();
        verify(organizationRepository).closeUnverifiedSignups(eq(cutoff), any(),
                eq("Closed automatically: the sign-up address was not verified within 7 days."));
    }
}
