package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BiFunction;

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

    private final CandidateEmailService email;
    private final JobRepository jobRepository;
    private final OrganizationRepository organizationRepository;
    private final String applicantPortalUrl;

    public CandidateUpdateEmails(CandidateEmailService email,
                                 JobRepository jobRepository,
                                 OrganizationRepository organizationRepository,
                                 @Value("${airral.notifications.email.app-base-url:https://apply.airral.com}")
                                 String applicantPortalUrl) {
        this.email = email;
        this.jobRepository = jobRepository;
        this.organizationRepository = organizationRepository;
        this.applicantPortalUrl = applicantPortalUrl;
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
                "you applied to this job on AIRRAL"));
    }

    /** To the candidate: the company booked an interview with them. */
    public void interviewBooked(Application application, Interview interview) {
        send(application, (job, company) -> new Message(
                "Interview with " + company.getName() + ": " + job.getTitle(),
                greeting(application)
                        + paragraph(escape(company.getName()) + " booked an interview with you for "
                                + strong(job.getTitle()) + ".")
                        + paragraph(strong(interviewTime(interview.getInterviewDate(), company)))
                        + paragraph(escape(company.getName()) + " will be in touch about how to join."),
                reason(application)));
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
                reason(application)));
    }

    private void send(Application application, BiFunction<Job, Organization, Message> compose) {
        String to = application.getApplicantEmail();
        if (to == null || to.isBlank() || application.getJobId() == null) return;

        jobRepository.findById(application.getJobId())
                .flatMap(job -> organizationRepository.findById(job.getOrganizationId())
                        .filter(CompanyVerificationService::isPublishable)
                        .map(company -> compose.apply(job, company)))
                .flatMap(message -> email.sendEmail(to, message.subject(),
                        email.wrapTransactional(message.subject(), message.bodyHtml(), message.reason())))
                .onErrorResume(e -> {
                    log.warn("Could not email the candidate on application {}: {}",
                            application.getId(), e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * The interview's day and time. The HR portal books it in the local time of
     * whoever booked it, so the company's time zone names that time when the
     * company has set one.
     */
    static String interviewTime(LocalDateTime at, Organization company) {
        if (at == null) return "At a time the company will confirm";
        String dayAndTime = DAY_AND_TIME.format(at);
        String zone = company.getTimezone();
        if (zone != null && !zone.isBlank()) {
            try {
                return dayAndTime + " " + ZONE.format(at.atZone(ZoneId.of(zone.trim())));
            } catch (DateTimeException ignored) {
                // A time zone the company typed that Java does not know.
            }
        }
        return dayAndTime + ", " + company.getName() + "'s local time";
    }

    private static String greeting(Application application) {
        String name = application.getApplicantName() == null ? "" : application.getApplicantName().trim();
        String first = name.isEmpty() ? "" : name.split("\\s+")[0];
        return paragraph(first.isEmpty() ? "Hello," : "Hi " + escape(first) + ",");
    }

    private static String reason(Application application) {
        return application.getApplicantId() != null
                ? "you applied to this job on AIRRAL"
                : "a company that hires with AIRRAL is considering you for this job";
    }

    private static String paragraph(String html) {
        return "<p style=\"margin:0 0 16px; font-size:15px; line-height:1.6; color:#111827;\">" + html + "</p>";
    }

    private static String strong(String text) {
        return "<strong>" + escape(text) + "</strong>";
    }

    private static String button(String url, String label) {
        return "<p style=\"margin:24px 0 0;\"><a href=\"" + escape(url) + "\" style=\"display:inline-block;"
                + " background:#007C6D; color:#ffffff; text-decoration:none; font-weight:600;"
                + " padding:10px 18px; border-radius:6px;\">" + escape(label) + "</a></p>";
    }

    private static String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }

    private record Message(String subject, String bodyHtml, String reason) {
        Message {
            // The subject carries the company's name and job title, which are
            // typed text: keep it one short line.
            subject = subject.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (subject.length() > 150) subject = subject.substring(0, 149) + "…";
        }
    }
}
