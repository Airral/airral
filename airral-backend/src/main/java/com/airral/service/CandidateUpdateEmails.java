package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Offer;
import com.airral.domain.Organization;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

import static com.airral.service.EmailHtml.button;
import static com.airral.service.EmailHtml.escape;
import static com.airral.service.EmailHtml.paragraph;
import static com.airral.service.EmailHtml.strong;

/**
 * The emails a candidate gets about one application: the company has it, an
 * interview is booked, or the company is not moving forward.
 *
 * <p>Only a company AIRRAL has verified emails candidates. A company waiting
 * for review can still add candidates and book interviews, but its name and
 * job titles are whatever it typed, and AIRRAL does not send those to people
 * from its own address until someone has checked the company.
 *
 * <p>Sending never holds up or fails the action behind it. Each email goes
 * out on its own, and a failure is logged.
 */
@Service
public class CandidateUpdateEmails {

    private static final Logger log = LoggerFactory.getLogger(CandidateUpdateEmails.class);

    private static final DateTimeFormatter DAY_AND_TIME =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'at' h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter ZONE = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);
    private static final DateTimeFormatter ANSWER_BY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter START_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final CandidateEmailService email;
    private final JobRepository jobRepository;
    private final OrganizationRepository organizationRepository;
    private final String applicantPortalUrl;
    private final String fromAddress;

    public CandidateUpdateEmails(CandidateEmailService email,
                                 JobRepository jobRepository,
                                 OrganizationRepository organizationRepository,
                                 @Value("${airral.notifications.email.app-base-url:https://apply.airral.com}")
                                 String applicantPortalUrl,
                                 @Value("${airral.notifications.email.from-address:notifications@airral.com}")
                                 String fromAddress) {
        this.email = email;
        this.jobRepository = jobRepository;
        this.organizationRepository = organizationRepository;
        this.applicantPortalUrl = applicantPortalUrl;
        this.fromAddress = fromAddress;
    }

    /** To an applicant who applied on AIRRAL: the company has their application. */
    public void applicationReceived(Application application) {
        send(application, (job, company) -> new Message(
                "We sent your application to " + company.getName(),
                greeting(application)
                        + paragraph(escape(company.getName()) + " has your application for "
                                + strong(job.getTitle()) + ", with the resume on your AIRRAL profile.")
                        + paragraph("You can see where it stands at any time.")
                        + button(applicantPortalUrl + "/tracker", "See your applications"),
                "you applied to this job on AIRRAL", null));
    }

    /**
     * To the candidate: the company booked an interview with them, with a
     * calendar file when the time can be placed.
     */
    public void interviewBooked(Application application, Interview interview) {
        send(application, (job, company) -> {
            String summary = "Interview with " + company.getName() + ": " + job.getTitle();
            String calendar = InterviewCalendar.event(interview, company, summary,
                    company.getName() + " will be in touch about how to join.", fromAddress,
                    List.of(new InterviewCalendar.Attendee(application.getApplicantName(), application.getApplicantEmail())),
                    Instant.now()).orElse(null);
            return new Message(
                    summary,
                    greeting(application)
                            + paragraph(escape(company.getName()) + " booked an interview with you for "
                                    + strong(job.getTitle()) + ".")
                            + paragraph(strong(interviewTime(interview, company))
                                    + " (" + minutes(interview) + " minutes)")
                            + paragraph(escape(company.getName()) + " will be in touch about how to join."
                                    + (calendar != null ? " The attached file adds it to your calendar." : "")),
                    reason(application),
                    calendar);
        });
    }

    /** To the candidate: the company is not moving forward with them. */
    public void notSelected(Application application) {
        send(application, (job, company) -> new Message(
                "Your application to " + company.getName(),
                greeting(application)
                        + paragraph("Thank you for your interest in " + strong(job.getTitle()) + " at "
                                + escape(company.getName()) + ". They have decided not to move forward"
                                + " with your application.")
                        + (application.getApplicantId() != null
                                ? paragraph("Your other applications are not affected.")
                                        + button(applicantPortalUrl + "/jobs", "Find more jobs")
                                : ""),
                reason(application), null));
    }

    /**
     * To the candidate: the company sent them an offer. An applicant reads the
     * full offer and answers on AIRRAL. Someone the company added by hand has no
     * account, so the email carries the offer letter and asks them to tell the
     * company their answer.
     */
    public void offerSent(Application application, Offer offer) {
        send(application, (job, company) -> {
            boolean hasAccount = application.getApplicantId() != null;
            String answerBy = offer.getExpiresAt() == null ? null : ANSWER_BY.format(offer.getExpiresAt());
            return new Message(
                    "Your offer from " + company.getName() + ": " + job.getTitle(),
                    greeting(application)
                            + paragraph(escape(company.getName()) + " is offering you the " + strong(job.getTitle())
                                    + " role.")
                            + paragraph("Pay: " + strong(money(offer)))
                            + (offer.getStartDate() != null
                                    ? paragraph("Start date: " + strong(START_DATE.format(offer.getStartDate())))
                                    : "")
                            + (hasAccount
                                    ? paragraph("Read the full offer and give your answer on AIRRAL"
                                            + (answerBy != null ? " by " + strong(answerBy) : "") + ".")
                                            + button(applicantPortalUrl + "/tracker", "See your offer")
                                    : (offer.getOfferLetter() != null && !offer.getOfferLetter().isBlank()
                                            ? EmailHtml.block(offer.getOfferLetter()) : "")
                                            + paragraph("Let " + escape(company.getName()) + " know your answer"
                                                    + (answerBy != null ? " by " + strong(answerBy) : "") + ".")),
                    reason(application),
                    null);
        });
    }

    /** "USD 85,000" style: the currency code, and the amount without trailing cents when there are none. */
    static String money(Offer offer) {
        if (offer.getSalary() == null) return "to be agreed";
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(offer.getSalary().stripTrailingZeros().scale() > 0 ? 2 : 0);
        format.setMaximumFractionDigits(2);
        return (offer.getCurrency() == null ? "USD" : offer.getCurrency()) + " " + format.format(offer.getSalary());
    }

    private void send(Application application, BiFunction<Job, Organization, Message> compose) {
        String to = application.getApplicantEmail();
        if (to == null || to.isBlank() || application.getJobId() == null) return;

        jobRepository.findById(application.getJobId())
                .flatMap(job -> organizationRepository.findById(job.getOrganizationId())
                        .filter(CompanyVerificationService::isPublishable)
                        .map(company -> compose.apply(job, company)))
                .flatMap(message -> email.sendEmail(to, message.subject(),
                        email.wrapTransactional(message.subject(), message.bodyHtml(), message.reason()),
                        message.calendar()))
                .onErrorResume(e -> {
                    log.warn("Could not email the candidate on application {}: {}",
                            application.getId(), e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * The interview's day and time, named with the zone it was booked in: the
     * booker's, or else the company's. Without either it is the company's
     * local time, which is what the booker entered.
     */
    static String interviewTime(Interview interview, Organization company) {
        LocalDateTime at = interview.getInterviewDate();
        if (at == null) return "At a time the company will confirm";
        String dayAndTime = DAY_AND_TIME.format(at);
        return InterviewCalendar.zoneOf(interview, company)
                .map(zone -> dayAndTime + " " + ZONE.format(at.atZone(zone)))
                .orElse(dayAndTime + ", " + company.getName() + "'s local time");
    }

    private static int minutes(Interview interview) {
        return interview.getDurationMinutes() != null ? interview.getDurationMinutes() : 60;
    }

    private static String greeting(Application application) {
        return EmailHtml.greeting(application.getApplicantName());
    }

    private static String reason(Application application) {
        return application.getApplicantId() != null
                ? "you applied to this job on AIRRAL"
                : "a company that hires with AIRRAL is considering you for this job";
    }

    private record Message(String subject, String bodyHtml, String reason, String calendar) {
        Message {
            // The subject carries the company's name and job title, which are
            // typed text: keep it one short line.
            subject = subject.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (subject.length() > 150) subject = subject.substring(0, 149) + "…";
        }
    }
}
