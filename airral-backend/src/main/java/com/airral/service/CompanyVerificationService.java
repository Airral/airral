package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.JobStatus;
import com.airral.exception.NotFoundException;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a company may show jobs to candidates.
 *
 * <p>Proving an email address proves the inbox, not the employer: a verified
 * {@code bob@gmail.com} who calls himself Stripe is still not Stripe. So a company
 * becomes VERIFIED one of two ways:
 * <ul>
 *   <li><b>DOMAIN</b> -- one of its people proves an address on the company's own
 *       domain, and that domain is not a free mail provider. Proving
 *       {@code bob@stripe.com} is evidence of working at stripe.com.</li>
 *   <li><b>ADMIN</b> -- a platform admin reviews it. Free-mail employers, and any
 *       company without a domain, wait here.</li>
 * </ul>
 *
 * <p>This mirrors how the large job boards separate the two questions: LinkedIn
 * verifies job posters by work email, and Indeed checks employers before their
 * postings go live, because fake job posts aimed at job seekers are a standing
 * scam and an email check alone does not stop them.
 */
@Service
public class CompanyVerificationService {

    private static final Logger log = LoggerFactory.getLogger(CompanyVerificationService.class);

    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";
    public static final String REJECTED = "REJECTED";

    /**
     * Domains anyone can get an address on. An address here proves the inbox and
     * nothing about an employer. Deliberately the common consumer providers only:
     * a company on a domain missing from this list still has to prove an address
     * on it, so an omission here can make review stricter, never weaker.
     */
    static final Set<String> FREE_MAIL_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "ymail.com", "rocketmail.com",
            "outlook.com", "hotmail.com", "live.com", "msn.com", "passport.com",
            "icloud.com", "me.com", "mac.com", "aol.com", "aim.com",
            "proton.me", "protonmail.com", "pm.me", "gmx.com", "gmx.net", "gmx.de",
            "mail.com", "zoho.com", "zohomail.com", "yandex.com", "yandex.ru",
            "tutanota.com", "tuta.io", "fastmail.com", "hey.com", "qq.com",
            "163.com", "126.com", "rediffmail.com", "web.de", "mail.ru",
            "comcast.net", "att.net", "verizon.net", "sbcglobal.net", "cox.net");

    private final OrganizationRepository organizationRepository;
    private final JobRepository jobRepository;
    private final InternalJobCatalogProjectionService projectionService;

    public CompanyVerificationService(OrganizationRepository organizationRepository,
                                      JobRepository jobRepository,
                                      @Lazy InternalJobCatalogProjectionService projectionService) {
        this.organizationRepository = organizationRepository;
        this.jobRepository = jobRepository;
        this.projectionService = projectionService;
    }

    public static boolean isPublishable(Organization organization) {
        return organization != null
                && Boolean.TRUE.equals(organization.getIsActive())
                && VERIFIED.equals(organization.getVerificationStatus());
    }

    public static String domainOf(String email) {
        if (email == null) return "";
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isFreeMail(String domain) {
        return domain == null || domain.isBlank() || FREE_MAIL_DOMAINS.contains(domain.toLowerCase(Locale.ROOT));
    }

    /**
     * The company domain AIRRAL records for a new employer: the domain of the
     * address they signed up with, or none for a free-mail address. Never the
     * value the request carried -- that is text the caller typed.
     */
    public static String companyDomainFor(String signupEmail) {
        String domain = domainOf(signupEmail);
        return isFreeMail(domain) ? null : domain;
    }

    /**
     * Called whenever one of a company's people proves their address. Verifies the
     * company by DOMAIN when the proven address sits on the company's own
     * non-free-mail domain; otherwise leaves it for review.
     */
    public Mono<Void> onEmailProven(User user) {
        if (user == null || user.getOrganizationId() == null) {
            return Mono.empty();
        }
        String emailDomain = domainOf(user.getEmail());
        return organizationRepository.findById(user.getOrganizationId())
                .filter(org -> PENDING.equals(org.getVerificationStatus()))
                .filter(org -> StringUtils.hasText(org.getDomain())
                        && !isFreeMail(emailDomain)
                        && emailDomain.equalsIgnoreCase(org.getDomain().trim()))
                // A second company proving a domain another company already proved
                // is a duplicate sign-up or a dispute, not something to decide
                // automatically -- it stays PENDING for an admin.
                .filterWhen(org -> organizationRepository.existsVerifiedDomainOtherThan(org.getDomain(), org.getId())
                        .map(taken -> {
                            if (taken) {
                                log.warn("Company {} proved {} but another company already holds it verified; left for review",
                                        org.getId(), org.getDomain());
                            }
                            return !taken;
                        }))
                .flatMap(org -> {
                    org.setVerificationStatus(VERIFIED);
                    org.setVerificationMethod("DOMAIN");
                    org.setVerifiedAt(LocalDateTime.now());
                    org.setVerificationNote("Verified by " + user.getEmail() + " proving an address on " + emailDomain);
                    org.setUpdatedAt(LocalDateTime.now());
                    return organizationRepository.save(org);
                })
                .doOnNext(org -> log.info("Company {} ({}) verified by domain via user {}",
                        org.getId(), org.getDomain(), user.getId()))
                .flatMap(this::republish)
                .then();
    }

    /** Admin review. Publishes the company's open jobs straight away. */
    public Mono<Organization> approve(Long organizationId, String note) {
        return setStatus(organizationId, VERIFIED, note);
    }

    /** Admin review. Takes any of the company's jobs out of the candidate catalogue. */
    public Mono<Organization> reject(Long organizationId, String note) {
        return setStatus(organizationId, REJECTED, note);
    }

    private Mono<Organization> setStatus(Long organizationId, String status, String note) {
        return organizationRepository.findById(organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Company not found")))
                .flatMap(org -> {
                    org.setVerificationStatus(status);
                    org.setVerificationMethod("ADMIN");
                    org.setVerifiedAt(VERIFIED.equals(status) ? LocalDateTime.now() : null);
                    org.setVerificationNote(StringUtils.hasText(note) ? note.trim() : null);
                    org.setUpdatedAt(LocalDateTime.now());
                    return organizationRepository.save(org);
                })
                .doOnNext(org -> log.warn("Company {} set to {} by admin review", org.getId(), status))
                .flatMap(org -> republish(org).thenReturn(org));
    }

    /**
     * Re-runs the catalogue projection for every open job the company has, so a
     * status change takes effect now rather than at the next reconcile.
     */
    private Mono<Void> republish(Organization organization) {
        return jobRepository.findByOrganizationIdAndStatus(organization.getId(), JobStatus.OPEN)
                .concatMap(projectionService::sync)
                .then();
    }
}
