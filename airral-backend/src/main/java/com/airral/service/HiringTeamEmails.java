package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.Offer;
import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.domain.enums.OfferStatus;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.airral.service.EmailHtml.button;
import static com.airral.service.EmailHtml.greeting;
import static com.airral.service.EmailHtml.paragraph;
import static com.airral.service.EmailHtml.strong;

/**
 * Emails to a company's own hiring team: its HR managers, and the hiring
 * manager of the job in question.
 *
 * <p>Sending never holds up or fails the action behind it; failures are
 * logged.
 */
@Service
public class HiringTeamEmails {

    private static final Logger log = LoggerFactory.getLogger(HiringTeamEmails.class);

    private final CandidateEmailService email;
    private final JobRepository jobRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final String hrPortalUrl;

    public HiringTeamEmails(CandidateEmailService email,
                            JobRepository jobRepository,
                            OrganizationRepository organizationRepository,
                            UserRepository userRepository,
                            @Value("${airral.auth.email-link.hr-url:https://app.airral.com}") String hrPortalUrl) {
        this.email = email;
        this.jobRepository = jobRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.hrPortalUrl = hrPortalUrl;
    }

    /** A candidate answered an offer. */
    public void offerAnswered(Offer offer, Application application) {
        boolean accepted = offer.getStatus() == OfferStatus.ACCEPTED;
        String candidate = application.getApplicantName() == null || application.getApplicantName().isBlank()
                ? application.getApplicantEmail() : application.getApplicantName().trim();

        jobRepository.findById(application.getJobId())
                .flatMap(job -> organizationRepository.findById(job.getOrganizationId())
                        .flatMap(company -> {
                            String subject = oneLine(candidate + (accepted ? " accepted" : " declined")
                                    + " the offer for " + job.getTitle());
                            return team(job).concatMap(member -> email.sendEmail(member.getEmail(), subject,
                                            email.wrapTransactional(subject, body(member, candidate, job, accepted),
                                                    "you hire for " + company.getName() + " on AIRRAL"))
                                    .onErrorResume(e -> {
                                        log.warn("Could not tell teammate {} about offer {}: {}",
                                                member.getId(), offer.getId(), e.getMessage());
                                        return Mono.empty();
                                    }))
                                    .then();
                        }))
                .onErrorResume(e -> {
                    log.warn("Could not tell the team about offer {}: {}", offer.getId(), e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    private String body(User member, String candidate, Job job, boolean accepted) {
        return greeting(member.getFirstName())
                + paragraph(strong(candidate) + (accepted ? " accepted" : " declined") + " the offer for "
                        + strong(job.getTitle()) + ".")
                + paragraph(accepted
                        ? "They are marked hired. From their page in Candidates you can close the job and let the"
                                + " other candidates know."
                        : "Their application is now withdrawn.")
                + button(hrPortalUrl + "/candidates", "Open Candidates");
    }

    /**
     * The company's active HR managers, and the job's hiring manager, each once.
     * A hiring manager named on the job before they moved to another company or
     * role, or out of hiring, is not told about its candidates.
     */
    private Flux<User> team(Job job) {
        Flux<User> hiringManager = job.getHiringManagerId() == null ? Flux.empty()
                : userRepository.findById(job.getHiringManagerId())
                        .filter(user -> Boolean.TRUE.equals(user.getIsActive())
                                && job.getOrganizationId() != null
                                && job.getOrganizationId().equals(user.getOrganizationId())
                                && (user.getRole() == UserRole.MANAGER || user.getRole() == UserRole.HR_MANAGER))
                        .flux();
        return Flux.concat(userRepository.findActiveHrManagers(job.getOrganizationId()), hiringManager)
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
                .distinct(User::getId);
    }

    private static String oneLine(String subject) {
        String line = subject.replaceAll("[\\r\\n\\t]+", " ").trim();
        return line.length() > 150 ? line.substring(0, 149) + "…" : line;
    }
}
