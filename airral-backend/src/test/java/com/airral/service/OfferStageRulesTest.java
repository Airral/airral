package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OfferRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What moving a candidate between stages does to their offer. A candidate at
 * the offer stage always has an offer to answer, and one who has left it
 * cannot answer one any more.
 */
class OfferStageRulesTest {

    private static final long ACME = 1L;
    private static final long HANA = 3L;

    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final OfferRepository offers = NoOffers.repository();
    private final ApplicationService service;
    private Application amys;

    OfferStageRulesTest() {
        JobRepository jobs = mock(JobRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        when(jobs.findById(10L)).thenReturn(Mono.just(Job.builder().id(10L).organizationId(ACME).title("Buyer").build()));
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));
        service = new ApplicationService(applications, jobs, mock(UserRepository.class), organizations,
                mock(CandidateProfileRepository.class), mock(CandidateUpdateEmails.class),
                mock(CandidateResumeDocumentRepository.class), offers);
    }

    @BeforeEach
    void setUp() {
        amys = at(ApplicationStatus.INTERVIEWED);
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private Application at(ApplicationStatus status) {
        Application application = Application.builder().id(100L).jobId(10L).applicantId(7L)
                .applicantName("Amy Adams").applicantEmail("amy@example.com").status(status).build();
        when(applications.findByIdAndOrganizationId(100L, ACME)).thenReturn(Mono.just(application));
        return application;
    }

    private Mono<?> move(ApplicationStatus status) {
        return service.updateApplicationStatus(100L, status, ACME, HANA, JobScope.wholeCompany(), false);
    }

    @Test
    @DisplayName("the offer stage is reached by sending an offer, not by moving the candidate there")
    void offerStageNeedsAnOffer() {
        StepVerifier.create(move(ApplicationStatus.OFFER_EXTENDED))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(BadRequestException.class)
                        .hasMessageContaining("Offers page"))
                .verify();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.INTERVIEWED);
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("while an offer waits for the candidate's answer, only their answer hires them")
    void hiringWaitsForTheAnswer() {
        amys = at(ApplicationStatus.OFFER_EXTENDED);
        when(offers.existsAwaitingAnswer(eq(100L), any())).thenReturn(Mono.just(true));

        StepVerifier.create(move(ApplicationStatus.HIRED)).expectError(ConflictException.class).verify();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.OFFER_EXTENDED);
        verify(offers, never()).closeOpen(anyLong(), any());
    }

    @Test
    @DisplayName("hiring a candidate with no offer waiting closes any draft")
    void hiringClosesDrafts() {
        StepVerifier.create(move(ApplicationStatus.HIRED)).expectNextCount(1).verifyComplete();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.HIRED);
        verify(offers).closeOpen(eq(100L), any());
    }

    @Test
    @DisplayName("turning a candidate down, or their withdrawing, closes their open offer")
    void closingTheApplicationClosesTheOffer() {
        for (ApplicationStatus closed : new ApplicationStatus[] {ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN}) {
            amys = at(ApplicationStatus.OFFER_EXTENDED);
            StepVerifier.create(move(closed)).expectNextCount(1).verifyComplete();
            assertThat(amys.getStatus()).isEqualTo(closed);
        }
        verify(offers, org.mockito.Mockito.times(2)).closeOpen(eq(100L), any());
    }

    @Test
    @DisplayName("moving a candidate back from the offer stage closes the offer they were sent")
    void movingBackClosesTheSentOffer() {
        amys = at(ApplicationStatus.OFFER_EXTENDED);

        StepVerifier.create(move(ApplicationStatus.INTERVIEWED)).expectNextCount(1).verifyComplete();
        verify(offers).closeSent(eq(100L), any());
        verify(offers, never()).closeOpen(anyLong(), any());
    }

    @Test
    @DisplayName("moves between earlier stages leave offers alone, drafts included")
    void earlierStagesLeaveOffersAlone() {
        StepVerifier.create(move(ApplicationStatus.SHORTLISTED)).expectNextCount(1).verifyComplete();
        verify(offers, never()).closeSent(anyLong(), any());
        verify(offers, never()).closeOpen(anyLong(), any());
    }

    @Test
    @DisplayName("moving a candidate to the stage they are at changes nothing")
    void sameStage() {
        StepVerifier.create(move(ApplicationStatus.INTERVIEWED)).expectNextCount(1).verifyComplete();
        verify(applications, never()).save(any());
    }
}
