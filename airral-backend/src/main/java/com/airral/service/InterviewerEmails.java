package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.airral.service.EmailHtml.block;
import static com.airral.service.EmailHtml.button;
import static com.airral.service.EmailHtml.escape;
import static com.airral.service.EmailHtml.greeting;
import static com.airral.service.EmailHtml.paragraph;
import static com.airral.service.EmailHtml.strong;

/**
 * The invitation each interviewer gets when they are put on an interview: the
 * time, who they are meeting, the team's notes, a link to their scorecard, and
 * a calendar file.
 *
 * <p>These go to the company's own teammates, at their account addresses, so
 * unlike candidate emails they do not wait for AIRRAL to verify the company.
 * Sending never holds up or fails the booking; failures are logged.
 */
@Service
public class InterviewerEmails {

    private static final Logger log = LoggerFactory.getLogger(InterviewerEmails.class);

    private final CandidateEmailService email;
    private final JobRepository jobRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final String hrPortalUrl;
    private final String fromAddress;

    public InterviewerEmails(CandidateEmailService email,
                             JobRepository jobRepository,
                             OrganizationRepository organizationRepository,
                             UserRepository userRepository,
                             @Value("${airral.auth.email-link.hr-url:https://app.airral.com}") String hrPortalUrl,
                             @Value("${airral.notifications.email.from-address:notifications@airral.com}")
                             String fromAddress) {
        this.email = email;
        this.jobRepository = jobRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.hrPortalUrl = hrPortalUrl;
        this.fromAddress = fromAddress;
    }

    public void invite(Interview interview, Application application, List<Long> interviewerIds) {
        if (interviewerIds == null || interviewerIds.isEmpty() || application.getJobId() == null) return;

        jobRepository.findById(application.getJobId())
                .flatMap(job -> organizationRepository.findById(job.getOrganizationId())
                        .flatMap(company -> Flux.fromIterable(interviewerIds)
                                .concatMap(userRepository::findById)
                                .collectList()
                                .flatMap(interviewers -> sendAll(interview, application, job, company, interviewers))))
                .onErrorResume(e -> {
                    log.warn("Could not invite the interviewers to interview {}: {}", interview.getId(), e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    private Mono<Void> sendAll(Interview interview, Application application, Job job, Organization company,
                               List<User> interviewers) {
        String candidate = application.getApplicantName() == null || application.getApplicantName().isBlank()
                ? application.getApplicantEmail() : application.getApplicantName().trim();
        String subject = oneLine("Interview: " + candidate + " for " + job.getTitle());
        String scorecardUrl = hrPortalUrl + "/interviews/scorecard?interviewId=" + interview.getId();
        String notes = interview.getNotes() == null || interview.getNotes().isBlank() ? null : interview.getNotes().trim();

        String calendar = InterviewCalendar.event(interview, company, subject,
                "Your scorecard: " + scorecardUrl + (notes != null ? "\n\nTeam notes: " + notes : ""),
                fromAddress,
                interviewers.stream()
                        .map(user -> new InterviewCalendar.Attendee(InterviewService.displayName(user), user.getEmail()))
                        .toList(),
                Instant.now()).orElse(null);

        return Flux.fromIterable(interviewers)
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
                .concatMap(user -> {
                    String others = interviewers.stream()
                            .filter(other -> !other.getId().equals(user.getId()))
                            .map(InterviewService::displayName)
                            .collect(Collectors.joining(", "));
                    String body = greeting(user.getFirstName())
                            + paragraph("You are interviewing " + strong(candidate) + " for " + strong(job.getTitle())
                                    + " at " + escape(company.getName()) + ".")
                            + paragraph(strong(CandidateUpdateEmails.interviewTime(interview, company)) + " ("
                                    + (interview.getDurationMinutes() != null ? interview.getDurationMinutes() : 60)
                                    + " minutes)")
                            + (others.isEmpty() ? "" : paragraph("With " + escape(others) + "."))
                            + (notes != null ? paragraph(strong("Notes from the team")) + block(notes) : "")
                            + paragraph("Your scorecard is where you rate the interview. Only you see it until you submit it.")
                            + button(scorecardUrl, "Open your scorecard");
                    return email.sendEmail(user.getEmail(), subject,
                                    email.wrapTransactional(subject, body,
                                            "you are an interviewer on this interview at " + company.getName()),
                                    calendar)
                            .onErrorResume(e -> {
                                log.warn("Could not invite interviewer {} to interview {}: {}",
                                        user.getId(), interview.getId(), e.getMessage());
                                return Mono.empty();
                            });
                })
                .then();
    }

    private static String oneLine(String subject) {
        String line = subject.replaceAll("[\\r\\n\\t]+", " ").trim();
        return line.length() > 150 ? line.substring(0, 149) + "…" : line;
    }
}
