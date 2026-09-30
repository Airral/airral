package com.airral.service;

import com.airral.controller.OfferController;
import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.Offer;
import com.airral.domain.Organization;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.OfferStatus;
import com.airral.dto.request.CreateOfferRequest;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OfferRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An offer from draft to answer, and who may take each step.
 */
class OfferFlowTest {

    private static final long ACME = 1L;
    private static final long AMY = 7L;       // applicant with an account
    private static final long SOMEONE = 8L;   // another applicant

    private final OfferRepository offers = mock(OfferRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final CandidateUpdateEmails candidateEmails = mock(CandidateUpdateEmails.class);
    private final HiringTeamEmails teamEmails = mock(HiringTeamEmails.class);
    private final OfferService service = new OfferService(offers, applications, jobs, organizations,
            candidateEmails, teamEmails);

    /** Amy applied on AIRRAL; Hal was added by HR and has no account. */
    private final Application amys = Application.builder().id(100L).jobId(10L).applicantId(AMY)
            .applicantName("Amy Adams").applicantEmail("amy@example.com").status(ApplicationStatus.INTERVIEWED).build();
    private final Application hals = Application.builder().id(101L).jobId(10L)
            .applicantName("Hal Hughes").applicantEmail("hal@example.com").status(ApplicationStatus.INTERVIEWED).build();

    @BeforeEach
    void setUp() {
        for (Application application : new Application[] {amys, hals}) {
            when(applications.findById(application.getId())).thenReturn(Mono.just(application));
            when(applications.findByIdAndOrganizationId(application.getId(), ACME)).thenReturn(Mono.just(application));
            when(offers.existsOpenByApplicationId(eq(application.getId()), any())).thenReturn(Mono.just(false));
        }
        when(offers.expireLapsed(anyLong(), any())).thenReturn(Mono.just(0L));
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jobs.findById(10L)).thenReturn(Mono.just(Job.builder().id(10L).organizationId(ACME).title("Store manager").build()));
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));
        when(offers.save(any(Offer.class))).thenAnswer(inv -> {
            Offer offer = inv.getArgument(0);
            if (offer.getId() == null) offer.setId(500L);
            return Mono.just(offer);
        });
    }

    /** An offer as stored. Anything past a draft was sent, and a sent one puts its candidate at the offer stage. */
    private Offer offer(long id, Application application, OfferStatus status) {
        Offer offer = Offer.builder().id(id).applicationId(application.getId()).jobId(10L)
                .salary(new BigDecimal("85000")).currency("USD").status(status).version(0L)
                .sentAt(status == OfferStatus.DRAFT ? null : LocalDateTime.now().minusDays(1))
                .expiresAt(LocalDateTime.now().plusDays(3)).build();
        if (status == OfferStatus.SENT) application.setStatus(ApplicationStatus.OFFER_EXTENDED);
        when(offers.findById(id)).thenReturn(Mono.just(offer));
        when(offers.findByIdAndOrganizationId(id, ACME)).thenReturn(Mono.just(offer));
        return offer;
    }

    @Test
    @DisplayName("an offer is for the application's own job, and a candidate has one open offer at a time")
    void create() {
        CreateOfferRequest request = CreateOfferRequest.builder().applicationId(100L).jobId(999L)
                .salary(new BigDecimal("85000")).build();

        StepVerifier.create(service.createOffer(request, ACME))
                .assertNext(created -> {
                    assertThat(created.getStatus()).isEqualTo(OfferStatus.DRAFT);
                    assertThat(created.getJobId()).isEqualTo(10L);
                    assertThat(created.getCompanyName()).isEqualTo("Acme");
                    assertThat(created.getCandidateHasAccount()).isTrue();
                })
                .verifyComplete();

        when(offers.existsOpenByApplicationId(eq(100L), any())).thenReturn(Mono.just(true));
        StepVerifier.create(service.createOffer(request, ACME)).expectError(ConflictException.class).verify();
        // A lapsed offer is recorded as expired first, so it does not count as open.
        InOrder order = inOrder(offers);
        order.verify(offers).expireLapsed(eq(100L), any());
        order.verify(offers).existsOpenByApplicationId(eq(100L), any());

        when(applications.findByIdAndOrganizationId(100L, 2L)).thenReturn(Mono.empty());
        StepVerifier.create(service.createOffer(request, 2L)).expectError(NotFoundException.class).verify();
    }

    @Test
    @DisplayName("sending a draft opens it until the end of a day, moves the candidate to offer, and emails them")
    void send() {
        Offer draft = offer(500L, amys, OfferStatus.DRAFT);

        StepVerifier.create(service.sendOffer(500L, ACME, null))
                .assertNext(sent -> {
                    assertThat(sent.getStatus()).isEqualTo(OfferStatus.SENT);
                    // With its offset, so a browser anywhere reads the same moment.
                    assertThat(sent.getExpiresAt().toInstant())
                            .isEqualTo(draft.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
                })
                .verifyComplete();

        // Acme has no time zone of its own, so the day ends in UTC, seven days from today there.
        ZonedDateTime deadline = draft.getExpiresAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
        assertThat(deadline.toLocalTime()).isEqualTo(LocalTime.of(23, 59, 59));
        assertThat(deadline.toLocalDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.OFFER_EXTENDED);
        verify(candidateEmails).offerSent(eq(amys), any(Offer.class));
    }

    @Test
    @DisplayName("a closed application takes no offer: none drafted, none sent")
    void closedApplicationsTakeNoOffer() {
        amys.setStatus(ApplicationStatus.REJECTED);
        offer(502L, amys, OfferStatus.DRAFT);

        StepVerifier.create(service.createOffer(CreateOfferRequest.builder().applicationId(100L)
                        .salary(new BigDecimal("85000")).build(), ACME))
                .expectError(ConflictException.class).verify();
        StepVerifier.create(service.sendOffer(502L, ACME, 7)).expectError(ConflictException.class).verify();
        verify(offers, never()).save(any());
        verify(candidateEmails, never()).offerSent(any(), any());
    }

    @Test
    @DisplayName("only a draft can be sent, for 1 to 60 days")
    void sendRules() {
        offer(501L, amys, OfferStatus.SENT);
        offer(502L, amys, OfferStatus.DRAFT);

        StepVerifier.create(service.sendOffer(501L, ACME, 7)).expectError(ConflictException.class).verify();
        StepVerifier.create(service.sendOffer(502L, ACME, 0)).expectError(BadRequestException.class).verify();
        StepVerifier.create(service.sendOffer(502L, ACME, 61)).expectError(BadRequestException.class).verify();
        verify(candidateEmails, never()).offerSent(any(), any());
    }

    @Test
    @DisplayName("the applicant accepts their own offer: they are hired, and the team hears")
    void applicantAccepts() {
        offer(500L, amys, OfferStatus.SENT);

        StepVerifier.create(service.answerAsApplicant(500L, AMY, true))
                .assertNext(answered -> assertThat(answered.getStatus()).isEqualTo(OfferStatus.ACCEPTED))
                .verifyComplete();

        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.HIRED);
        ArgumentCaptor<Offer> told = ArgumentCaptor.forClass(Offer.class);
        verify(teamEmails).offerAnswered(told.capture(), eq(amys));
        assertThat(told.getValue().getRespondedAt()).isNotNull();
    }

    @Test
    @DisplayName("declining withdraws the application")
    void applicantDeclines() {
        offer(500L, amys, OfferStatus.SENT);

        StepVerifier.create(service.answerAsApplicant(500L, AMY, false))
                .assertNext(answered -> assertThat(answered.getStatus()).isEqualTo(OfferStatus.DECLINED))
                .verifyComplete();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("nobody answers someone else's offer, a draft, an expired one, or one already answered")
    void answerRules() {
        offer(500L, amys, OfferStatus.SENT);
        offer(501L, amys, OfferStatus.DRAFT);
        offer(502L, amys, OfferStatus.SENT).setExpiresAt(LocalDateTime.now().minusMinutes(1));
        offer(503L, amys, OfferStatus.ACCEPTED);

        StepVerifier.create(service.answerAsApplicant(500L, SOMEONE, true)).expectError(NotFoundException.class).verify();
        StepVerifier.create(service.answerAsApplicant(501L, AMY, true)).expectError(NotFoundException.class).verify();
        StepVerifier.create(service.answerAsApplicant(502L, AMY, true)).expectError(ConflictException.class).verify();
        StepVerifier.create(service.answerAsApplicant(503L, AMY, false)).expectError(ConflictException.class).verify();
        verify(teamEmails, never()).offerAnswered(any(), any());
    }

    @Test
    @DisplayName("a draft withdrawn before it was sent does not exist for the applicant")
    void withdrawnDraftIsNotTheApplicants() {
        offer(504L, amys, OfferStatus.WITHDRAWN).setSentAt(null);

        StepVerifier.create(service.answerAsApplicant(504L, AMY, true))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(NotFoundException.class)
                        .hasMessage("Offer not found"))
                .verify();
    }

    @Test
    @DisplayName("a candidate turned down after the offer went out cannot accept it")
    void answerNeedsTheOfferStage() {
        offer(500L, amys, OfferStatus.SENT);
        amys.setStatus(ApplicationStatus.REJECTED);

        StepVerifier.create(service.answerAsApplicant(500L, AMY, true)).expectError(ConflictException.class).verify();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        verify(offers, never()).save(any());
        verify(teamEmails, never()).offerAnswered(any(), any());
    }

    @Test
    @DisplayName("an offer withdrawn while the candidate accepts it: the accept loses, and nothing of it is kept")
    void withdrawAndAcceptRace() {
        offer(500L, amys, OfferStatus.SENT);
        // The row changed since it was read: its version check fails.
        when(offers.save(any(Offer.class))).thenReturn(Mono.error(
                new org.springframework.dao.OptimisticLockingFailureException("offers row 500 changed")));

        StepVerifier.create(service.answerAsApplicant(500L, AMY, true))
                .expectError(org.springframework.dao.OptimisticLockingFailureException.class)
                .verify();
        verify(applications, never()).save(any());
        verify(teamEmails, never()).offerAnswered(any(), any());
    }

    @Test
    @DisplayName("HR records the answer only for a candidate it added by hand")
    void hrRecordsAnswers() {
        offer(500L, amys, OfferStatus.SENT);
        offer(501L, hals, OfferStatus.SENT);

        StepVerifier.create(service.recordAnswer(500L, ACME, true))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ConflictException.class)
                        .hasMessageContaining("answers the offer themselves"))
                .verify();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.OFFER_EXTENDED);

        StepVerifier.create(service.recordAnswer(501L, ACME, true))
                .assertNext(answered -> assertThat(answered.getStatus()).isEqualTo(OfferStatus.ACCEPTED))
                .verifyComplete();
        assertThat(hals.getStatus()).isEqualTo(ApplicationStatus.HIRED);
    }

    @Test
    @DisplayName("an unanswered offer can be withdrawn, and the candidate goes back to interviewed; an answered one cannot")
    void withdraw() {
        offer(500L, amys, OfferStatus.SENT);
        offer(501L, amys, OfferStatus.ACCEPTED);

        StepVerifier.create(service.withdrawOffer(500L, ACME))
                .assertNext(withdrawn -> assertThat(withdrawn.getStatus()).isEqualTo(OfferStatus.WITHDRAWN))
                .verifyComplete();
        assertThat(amys.getStatus()).isEqualTo(ApplicationStatus.INTERVIEWED);
        StepVerifier.create(service.withdrawOffer(501L, ACME)).expectError(ConflictException.class).verify();
    }

    @Test
    @DisplayName("withdrawing a draft leaves the candidate's stage alone: it never reached them")
    void withdrawDraft() {
        offer(502L, hals, OfferStatus.DRAFT);

        StepVerifier.create(service.withdrawOffer(502L, ACME)).expectNextCount(1).verifyComplete();
        assertThat(hals.getStatus()).isEqualTo(ApplicationStatus.INTERVIEWED);
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("an offer sent from New York is open until the end of a New York day, even across a clock change")
    void deadlineInTheCompanysZone() {
        Organization newYork = Organization.builder().id(ACME).timezone("America/New_York").build();
        // Saturday 7 March 2026, 3pm in New York; the clocks go forward that night.
        ZonedDateTime now = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, ZoneOffset.UTC);

        assertThat(OfferDeadline.endOfDay(1, newYork, now))
                .isEqualTo(LocalDateTime.of(2026, 3, 9, 3, 59, 59));   // 23:59:59 EDT on the 8th
        assertThat(OfferDeadline.endOfDay(1, Organization.builder().timezone("Mars/Olympus").build(), now))
                .isEqualTo(LocalDateTime.of(2026, 3, 8, 23, 59, 59));  // an unknown zone falls back to UTC
    }

    @Test
    @DisplayName("the offer email gives the deadline in the company's time")
    void emailDeadlineInTheCompanysZone() {
        Organization newYork = Organization.builder().id(ACME).timezone("America/New_York").build();
        LocalDateTime deadline = ZonedDateTime.of(2026, 10, 6, 23, 59, 59, 0, ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

        assertThat(CandidateUpdateEmails.ANSWER_BY.format(OfferDeadline.inCompanyZone(deadline, newYork)))
                .isEqualTo("Tuesday 6 October, 11:59 PM EDT");
    }

    @Test
    @DisplayName("a sent offer past its date reads as expired")
    void expiredReadsExpired() {
        offer(500L, amys, OfferStatus.SENT).setExpiresAt(LocalDateTime.now().minusDays(1));

        StepVerifier.create(service.getOfferById(500L, ACME))
                .assertNext(read -> assertThat(read.getStatus()).isEqualTo(OfferStatus.EXPIRED))
                .verifyComplete();
    }

    @Test
    @DisplayName("the controller answers as the applicant for an applicant, and records for HR")
    void controllerRoutesByRole() {
        OfferService stub = mock(OfferService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getRoleFromToken("amy")).thenReturn("APPLICANT");
        when(jwt.getUserIdFromToken("amy")).thenReturn(AMY);
        when(jwt.getRoleFromToken("hr")).thenReturn("HR_MANAGER");
        when(jwt.getOrganizationIdFromToken("hr")).thenReturn(ACME);
        when(stub.answerAsApplicant(any(), any(), eq(true))).thenReturn(Mono.empty());
        when(stub.recordAnswer(any(), any(), eq(false))).thenReturn(Mono.empty());
        OfferController controller = new OfferController(stub, jwt);

        controller.acceptOffer(500L, "Bearer amy").block();
        controller.declineOffer(501L, "Bearer hr").block();

        verify(stub).answerAsApplicant(500L, AMY, true);
        verify(stub).recordAnswer(501L, ACME, false);
    }

    @Test
    @DisplayName("pay reads as a currency code and an amount")
    void money() {
        assertThat(CandidateUpdateEmails.money(Offer.builder().salary(new BigDecimal("85000.00")).currency("USD").build()))
                .isEqualTo("USD 85,000");
        assertThat(CandidateUpdateEmails.money(Offer.builder().salary(new BigDecimal("41.5")).currency("EUR").build()))
                .isEqualTo("EUR 41.50");
    }
}
