package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.dto.request.ContactMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Posts to the AIRRAL team's Slack channel, through an incoming webhook.
 *
 * <p>For things a person at AIRRAL has to act on: a new company waiting for
 * review, a message from the contact form. With no webhook configured (local
 * runs, tests) nothing is posted.
 *
 * <p>Everything a visitor typed is escaped before it is posted. Slack reads
 * {@code <!channel>} as a mention of everyone in the channel and
 * {@code <https://...|text>} as a link, so an unescaped company name could
 * ping the whole team or dress up a phishing link.
 */
@Service
public class TeamAlerts {

    private static final Logger log = LoggerFactory.getLogger(TeamAlerts.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String webhookUrl;
    private final String adminUrl;

    public TeamAlerts(WebClient.Builder webClientBuilder,
                      @Value("${airral.alerts.slack-webhook-url:}") String webhookUrl,
                      @Value("${airral.alerts.admin-url:https://admin.airral.com}") String adminUrl) {
        this.webClient = webClientBuilder.build();
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.adminUrl = adminUrl;
    }

    public boolean isConfigured() {
        return !webhookUrl.isEmpty();
    }

    /**
     * Tells the team a company signed up and is waiting for review. Fire and
     * forget: a slow or failing Slack never holds up or breaks a signup.
     */
    public void newCompany(Organization organization, User hrManager) {
        if (!isConfigured()) {
            log.info("Company {} is waiting for review (no Slack webhook configured)", organization.getId());
            return;
        }
        try {
            post(newCompanyText(organization, hrManager)).subscribe(
                    null,
                    error -> log.warn("Could not post company {} to Slack: {}", organization.getId(), error.getMessage()));
        } catch (RuntimeException error) {
            log.warn("Could not post company {} to Slack: {}", organization.getId(), error.getMessage());
        }
    }

    /**
     * Delivers a contact-form message. Errors when it could not be posted, so
     * the visitor can be told to email instead of the message being lost.
     */
    public Mono<Void> contactMessage(ContactMessageRequest message) {
        if (!isConfigured()) {
            return Mono.error(new IllegalStateException("No Slack webhook configured"));
        }
        return Mono.defer(() -> post(contactText(message)));
    }

    String newCompanyText(Organization organization, User hrManager) {
        String domain = StringUtils.hasText(organization.getDomain()) ? organization.getDomain() : "no company domain";
        StringBuilder text = new StringBuilder()
                .append("New company waiting for review: *").append(escape(organization.getName())).append("* (")
                .append(escape(domain)).append(")\n")
                .append("Signed up by ").append(escape(fullName(hrManager)))
                .append(", ").append(escape(hrManager.getEmail()));
        if (StringUtils.hasText(hrManager.getPhone())) {
            text.append(", ").append(escape(hrManager.getPhone()));
        }
        return text.append("\n<").append(adminUrl).append("/companies|Review it in the admin portal>").toString();
    }

    String contactText(ContactMessageRequest message) {
        String subject = StringUtils.hasText(message.getSubject()) ? message.getSubject() : "(no subject)";
        return "New message from the contact form\n"
                + "From: " + escape(message.getName()) + ", " + escape(message.getEmail()) + "\n"
                + "Subject: " + escape(subject) + "\n\n"
                + escape(message.getMessage());
    }

    /** Slack's own escaping for message text: the three characters its markup is built from. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fullName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? "someone" : name;
    }

    private Mono<Void> post(String text) {
        return webClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .then();
    }
}
