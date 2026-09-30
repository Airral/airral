package com.airral.controller;

import com.airral.config.ClientIpConfig;
import com.airral.dto.request.ContactMessageRequest;
import com.airral.security.LoginThrottle;
import com.airral.service.TeamAlerts;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The website's contact form.
 */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);

    static final String SENT = "Thanks. Your message is with us, and we will reply within one business day.";
    static final String NOT_SENT = "Your message could not be sent. Email contact@airral.com instead.";

    private final TeamAlerts teamAlerts;
    private final LoginThrottle loginThrottle;

    public ContactController(TeamAlerts teamAlerts, LoginThrottle loginThrottle) {
        this.teamAlerts = teamAlerts;
        this.loginThrottle = loginThrottle;
    }

    /**
     * Send a message to the AIRRAL team.
     * POST /api/contact
     *
     * <p>Public, like the form, and throttled on the caller's address with the
     * same bucket as sign-in and registration. The message goes to the team's
     * Slack channel. When it cannot be posted the visitor is told to email
     * instead, so nobody is thanked for a message that was then lost.
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> send(
            @Valid @RequestBody ContactMessageRequest request,
            ServerWebExchange exchange) {

        String address = ClientIpConfig.clientAddress(exchange);

        return loginThrottle.checkAddress(address)
                .then(loginThrottle.recordAddressAttempt(address))
                .then(Mono.defer(() -> teamAlerts.contactMessage(request))
                        .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED)
                                .body(Map.<String, Object>of("message", SENT)))
                        .onErrorResume(error -> {
                            log.warn("Contact message not delivered: {}", TeamAlerts.describe(error));
                            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(Map.<String, Object>of("message", NOT_SENT)));
                        }));
    }
}
