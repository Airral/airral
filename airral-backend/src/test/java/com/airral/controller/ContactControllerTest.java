package com.airral.controller;

import com.airral.dto.request.ContactMessageRequest;
import com.airral.exception.TooManyLoginAttemptsException;
import com.airral.security.LoginThrottle;
import com.airral.service.TeamAlerts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contact form's promise: a visitor is thanked only for a message the team
 * received, and told to email when it did not arrive.
 */
class ContactControllerTest {

    private final TeamAlerts alerts = mock(TeamAlerts.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private final ContactController controller = new ContactController(alerts, throttle);
    private final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/contact").remoteAddress(new InetSocketAddress("203.0.113.9", 443)));

    private static ContactMessageRequest message() {
        return ContactMessageRequest.builder().name("Amy").email("amy@acme.io").message("Hello").build();
    }

    @BeforeEach
    void setUp() {
        when(throttle.checkAddress(any())).thenReturn(Mono.empty());
        when(throttle.recordAddressAttempt(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("a delivered message is accepted")
    void deliveredMessageIsAccepted() {
        when(alerts.contactMessage(any())).thenReturn(Mono.empty());

        StepVerifier.create(controller.send(message(), exchange))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                    assertThat(response.getBody()).containsEntry("message", ContactController.SENT);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a message that could not be delivered tells the visitor to email instead")
    void undeliveredMessageSaysSo() {
        when(alerts.contactMessage(any())).thenReturn(Mono.error(new IllegalStateException("No Slack webhook configured")));

        StepVerifier.create(controller.send(message(), exchange))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getBody().get("message").toString()).contains("contact@airral.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a throttled address is turned away before anything is posted")
    void throttledAddressPostsNothing() {
        when(throttle.checkAddress(any())).thenReturn(Mono.error(new TooManyLoginAttemptsException(15)));

        StepVerifier.create(controller.send(message(), exchange))
                .expectError(TooManyLoginAttemptsException.class)
                .verify();
        verify(alerts, never()).contactMessage(any());
    }
}
