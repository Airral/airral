package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.dto.request.ContactMessageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What reaches the team's Slack channel, and what happens when Slack does not
 * take it. The webhook is a stub that answers with a chosen status.
 */
class TeamAlertsTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/test";
    private static final String ADMIN = "https://admin.airral.com";

    private final AtomicInteger posts = new AtomicInteger();

    private WebClient.Builder slackAnswering(HttpStatus status) {
        return WebClient.builder().exchangeFunction(request -> {
            posts.incrementAndGet();
            return Mono.just(ClientResponse.create(status).build());
        });
    }

    private static ContactMessageRequest message() {
        return ContactMessageRequest.builder()
                .name("Amy Adams").email("amy@acme.io").subject("Pricing").message("Do you do annual billing?")
                .build();
    }

    @Test
    @DisplayName("text a visitor typed cannot mention the whole channel or add a link")
    void escapesSlackMarkup() {
        assertThat(TeamAlerts.escape("<!channel> <https://evil.example|Pay here> & co"))
                .isEqualTo("&lt;!channel&gt; &lt;https://evil.example|Pay here&gt; &amp; co");
    }

    @Test
    @DisplayName("a new-company alert names the company, who signed up, and where to review it")
    void newCompanyText() {
        TeamAlerts alerts = new TeamAlerts(WebClient.builder(), WEBHOOK, ADMIN);

        String text = alerts.newCompanyText(
                Organization.builder().id(4L).name("Acme <!here>").domain("acme.io").build(),
                User.builder().firstName("Amy").lastName("Adams").email("amy@acme.io").phone("555-0100").build());

        assertThat(text)
                .contains("Acme &lt;!here&gt;")
                .contains("(acme.io)")
                .contains("Amy Adams, amy@acme.io, 555-0100")
                .contains("<https://admin.airral.com/companies|Review it in the admin portal>")
                .doesNotContain("<!here>");
    }

    @Test
    @DisplayName("a contact message is posted to the webhook")
    void postsContactMessage() {
        TeamAlerts alerts = new TeamAlerts(slackAnswering(HttpStatus.OK), WEBHOOK, ADMIN);

        StepVerifier.create(alerts.contactMessage(message())).verifyComplete();

        assertThat(posts).hasValue(1);
    }

    @Test
    @DisplayName("a contact message Slack refuses is reported, not dropped")
    void slackRefusalIsAnError() {
        TeamAlerts alerts = new TeamAlerts(slackAnswering(HttpStatus.INTERNAL_SERVER_ERROR), WEBHOOK, ADMIN);

        StepVerifier.create(alerts.contactMessage(message())).expectError().verify();
    }

    @Test
    @DisplayName("with no webhook, a contact message is an error and nothing is posted")
    void noWebhookContactMessage() {
        TeamAlerts alerts = new TeamAlerts(slackAnswering(HttpStatus.OK), "", ADMIN);

        StepVerifier.create(alerts.contactMessage(message())).expectError(IllegalStateException.class).verify();
        assertThat(posts).hasValue(0);
    }

    @Test
    @DisplayName("with no webhook, a new company is only logged")
    void noWebhookNewCompany() {
        TeamAlerts alerts = new TeamAlerts(slackAnswering(HttpStatus.OK), "  ", ADMIN);

        alerts.newCompany(Organization.builder().id(4L).name("Acme").build(), User.builder().email("amy@acme.io").build());

        assertThat(posts).hasValue(0);
    }
}
