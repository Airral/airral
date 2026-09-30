package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Offer;
import com.airral.domain.Organization;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.OfferStatus;
import com.airral.dto.request.CreateOfferRequest;
import com.airral.dto.response.OfferResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OfferRepository;
import com.airral.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Offers, from draft to answer.
 *
 * <p>A company drafts an offer, sends it, and can withdraw it until it is
 * answered. The candidate answers it: an applicant with an AIRRAL account on
 * apply.airral.com, and nobody else can answer for them. A candidate the
 * company added by hand has no account, so HR records their answer instead.
 * Accepting marks the application hired; declining withdraws it.
 *
 * <p>Sending an offer is what puts an application at the offer stage, and an
 * application that leaves that stage any other way takes its offer with it
 * (see ApplicationService), so an offer is only ever answered by a candidate
 * still being offered the job.
 */
@Service
public class OfferService {

    static final int DEFAULT_DAYS_TO_ANSWER = 7;

    private final OfferRepository offerRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final OrganizationRepository organizationRepository;
    private final CandidateUpdateEmails candidateEmails;
    private final HiringTeamEmails teamEmails;

    public OfferService(OfferRepository offerRepository,
                        ApplicationRepository applicationRepository,
                        JobRepository jobRepository,
                        OrganizationRepository organizationRepository,
                        CandidateUpdateEmails candidateEmails,
                        HiringTeamEmails teamEmails) {
        this.offerRepository = offerRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.organizationRepository = organizationRepository;
        this.candidateEmails = candidateEmails;
        this.teamEmails = teamEmails;
    }

    /** Stages an application does not come back from by way of an offer. */
    static final Set<ApplicationStatus> CLOSED =
            EnumSet.of(ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN);

    /**
     * Draft an offer for one of the company's candidates. It is always for the
     * application's own job, and a candidate has one open offer at a time: the
     * database holds that rule too, so two drafts made at once cannot both land.
     */
    @Transactional
    public Mono<OfferResponse> createOffer(CreateOfferRequest request, Long organizationId) {
        LocalDateTime now = LocalDateTime.now();
        return applicationRepository.findByIdAndOrganizationId(request.getApplicationId(), organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMap(application -> stillOpen(application).thenReturn(application))
                .flatMap(application -> offerRepository.expireLapsed(application.getId(), now)
                        .then(offerRepository.existsOpenByApplicationId(application.getId(), now))
                        .flatMap(open -> open
                                ? Mono.<Offer>error(new ConflictException(
                                        "This candidate already has an open offer. Withdraw it before making another."))
                                : offerRepository.save(Offer.builder()
                                        .applicationId(application.getId())
                                        .jobId(application.getJobId())
                                        .salary(request.getSalary())
                                        .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                                        .startDate(request.getStartDate())
                                        .offerLetter(request.getOfferLetter())
                                        .benefits(request.getBenefits())
                                        .contingencies(request.getContingencies())
                                        .status(OfferStatus.DRAFT)
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build())))
                .flatMap(this::toOfferResponse);
    }

    public Mono<OfferResponse> getOfferById(Long id, Long organizationId) {
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(this::toOfferResponse);
    }

    public Flux<OfferResponse> getAllOffers(Long organizationId) {
        return offerRepository.findAllByOrganizationId(organizationId)
                .concatMap(this::toOfferResponse);
    }

    public Flux<OfferResponse> getOffersByApplication(Long applicationId, Long organizationId) {
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .flatMapMany(app -> offerRepository.findByApplicationId(applicationId))
                .concatMap(this::toOfferResponse);
    }

    /** An applicant's own offers, once sent. */
    public Flux<OfferResponse> getMyOffers(Long applicantId) {
        return offerRepository.findSentByApplicantId(applicantId)
                .concatMap(this::toOfferResponse);
    }

    /**
     * Send a draft to the candidate. They have until the end of the day
     * {@code daysToAnswer} days from now, in the company's time zone, and are
     * emailed the offer, once the change is saved, when AIRRAL has verified
     * the company.
     */
    @Transactional
    public Mono<OfferResponse> sendOffer(Long id, Long organizationId, Integer daysToAnswer) {
        int days = daysToAnswer != null ? daysToAnswer : DEFAULT_DAYS_TO_ANSWER;
        if (days < 1 || days > 60) {
            return Mono.error(new BadRequestException("An offer is open for 1 to 60 days"));
        }
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(offer -> offer.getStatus() != OfferStatus.DRAFT
                        ? Mono.<Offer>error(new ConflictException("Only a draft can be sent. This offer is "
                                + offer.getStatus().name().toLowerCase() + "."))
                        : applicationRepository.findById(offer.getApplicationId())
                                .flatMap(application -> stillOpen(application)
                                        .then(companyOf(application))
                                        .flatMap(company -> {
                                            LocalDateTime now = LocalDateTime.now();
                                            offer.setStatus(OfferStatus.SENT);
                                            offer.setSentAt(now);
                                            offer.setExpiresAt(OfferDeadline.endOfDay(days, company.orElse(null),
                                                    ZonedDateTime.now()));
                                            offer.setUpdatedAt(now);
                                            application.setStatus(ApplicationStatus.OFFER_EXTENDED);
                                            application.setUpdatedAt(now);
                                            return offerRepository.save(offer)
                                                    .flatMap(sent -> applicationRepository.save(application)
                                                            .then(AfterCommit.run(() -> candidateEmails.offerSent(application, sent)))
                                                            .thenReturn(sent));
                                        })))
                .flatMap(this::toOfferResponse);
    }

    /**
     * Take back an offer that has not been answered. A candidate who had been
     * sent it goes back to the stage before the offer.
     */
    @Transactional
    public Mono<OfferResponse> withdrawOffer(Long id, Long organizationId) {
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(offer -> {
                    if (offer.getStatus() != OfferStatus.DRAFT && offer.getStatus() != OfferStatus.SENT) {
                        return Mono.error(new ConflictException("This offer is already "
                                + offer.getStatus().name().toLowerCase() + "."));
                    }
                    boolean wasSent = offer.getStatus() == OfferStatus.SENT;
                    LocalDateTime now = LocalDateTime.now();
                    offer.setStatus(OfferStatus.WITHDRAWN);
                    offer.setUpdatedAt(now);
                    return offerRepository.save(offer)
                            .flatMap(withdrawn -> !wasSent ? Mono.just(withdrawn)
                                    : applicationRepository.findById(withdrawn.getApplicationId())
                                            .filter(application -> application.getStatus() == ApplicationStatus.OFFER_EXTENDED)
                                            .flatMap(application -> {
                                                application.setStatus(ApplicationStatus.INTERVIEWED);
                                                application.setUpdatedAt(now);
                                                return applicationRepository.save(application);
                                            })
                                            .thenReturn(withdrawn));
                })
                .flatMap(this::toOfferResponse);
    }

    /**
     * The applicant answers their own offer. One they were never sent does not
     * exist as far as they are concerned, withdrawn draft or not.
     */
    @Transactional
    public Mono<OfferResponse> answerAsApplicant(Long id, Long applicantId, boolean accept) {
        return offerRepository.findById(id)
                .filter(offer -> offer.getSentAt() != null)
                .filterWhen(offer -> applicationRepository.findById(offer.getApplicationId())
                        .map(application -> applicantId != null && applicantId.equals(application.getApplicantId()))
                        .defaultIfEmpty(false))
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(offer -> answer(offer, accept))
                .flatMap(this::toOfferResponse);
    }

    /**
     * HR records the answer of a candidate it added by hand. Anyone with an
     * AIRRAL account answers for themselves, so a company cannot accept an
     * offer on their behalf.
     */
    @Transactional
    public Mono<OfferResponse> recordAnswer(Long id, Long organizationId, boolean accept) {
        return offerRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Offer not found")))
                .flatMap(offer -> applicationRepository.findById(offer.getApplicationId())
                        .flatMap(application -> application.getApplicantId() != null
                                ? Mono.<Offer>error(new ConflictException(
                                        "This candidate has an AIRRAL account and answers the offer themselves."))
                                : answer(offer, accept)))
                .flatMap(this::toOfferResponse);
    }

    private Mono<Offer> answer(Offer offer, boolean accept) {
        if (offer.getStatus() != OfferStatus.SENT) {
            return Mono.error(new ConflictException("This offer is " + offer.getStatus().name().toLowerCase()
                    + " and can no longer be answered."));
        }
        if (isExpired(offer)) {
            return Mono.error(new ConflictException("This offer expired and can no longer be answered."));
        }
        LocalDateTime now = LocalDateTime.now();
        return applicationRepository.findById(offer.getApplicationId())
                .flatMap(application -> {
                    // Leaving the offer stage closes the offer, so this holds already;
                    // it is checked again because an answer cannot be taken back.
                    if (application.getStatus() != ApplicationStatus.OFFER_EXTENDED) {
                        return Mono.<Offer>error(new ConflictException(
                                "This offer can no longer be answered: the application has moved on."));
                    }
                    offer.setStatus(accept ? OfferStatus.ACCEPTED : OfferStatus.DECLINED);
                    offer.setRespondedAt(now);
                    offer.setUpdatedAt(now);
                    application.setStatus(accept ? ApplicationStatus.HIRED : ApplicationStatus.WITHDRAWN);
                    application.setUpdatedAt(now);
                    // The offer first: if it changed since it was read (withdrawn a
                    // moment ago), its version check fails and nothing is saved.
                    return offerRepository.save(offer)
                            .flatMap(answered -> applicationRepository.save(application)
                                    .then(AfterCommit.run(() -> teamEmails.offerAnswered(answered, application)))
                                    .thenReturn(answered));
                });
    }

    /** Refuses an offer step for an application that is closed: hired, turned down or withdrawn. */
    private static Mono<Void> stillOpen(Application application) {
        return CLOSED.contains(application.getStatus())
                ? Mono.error(new ConflictException("This candidate's application is "
                        + application.getStatus().name().toLowerCase() + ", so it can't take an offer."))
                : Mono.empty();
    }

    /** The company the application's job belongs to, for its time zone. */
    private Mono<Optional<Organization>> companyOf(Application application) {
        return jobRepository.findById(application.getJobId())
                .flatMap(job -> organizationRepository.findById(job.getOrganizationId()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    static boolean isExpired(Offer offer) {
        return offer.getExpiresAt() != null && offer.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private Mono<OfferResponse> toOfferResponse(Offer offer) {
        return applicationRepository.findById(offer.getApplicationId())
                .flatMap(application -> jobRepository.findById(application.getJobId())
                        .flatMap(job -> organizationRepository.findById(job.getOrganizationId())
                                .map(company -> company.getName() == null ? "" : company.getName())
                                .defaultIfEmpty("")
                                .map(companyName -> OfferResponse.builder()
                                        .id(offer.getId())
                                        .applicationId(offer.getApplicationId())
                                        .jobId(application.getJobId())
                                        .candidateName(application.getApplicantName())
                                        .candidateEmail(application.getApplicantEmail())
                                        .candidateHasAccount(application.getApplicantId() != null)
                                        .jobTitle(job.getTitle())
                                        .companyName(companyName.isBlank() ? null : companyName)
                                        .salary(offer.getSalary())
                                        .currency(offer.getCurrency())
                                        .startDate(offer.getStartDate())
                                        .offerLetter(offer.getOfferLetter())
                                        .benefits(offer.getBenefits())
                                        .contingencies(offer.getContingencies())
                                        // A sent offer past its date reads as expired; nothing answers it now.
                                        .status(offer.getStatus() == OfferStatus.SENT && isExpired(offer)
                                                ? OfferStatus.EXPIRED : offer.getStatus())
                                        .sentAt(offer.getSentAt())
                                        .expiresAt(OfferDeadline.withOffset(offer.getExpiresAt()))
                                        .respondedAt(offer.getRespondedAt())
                                        .createdAt(offer.getCreatedAt())
                                        .updatedAt(offer.getUpdatedAt())
                                        .build())));
    }
}
