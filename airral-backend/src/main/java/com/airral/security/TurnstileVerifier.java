package com.airral.security;

import com.airral.exception.BadRequestException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Cloudflare Turnstile: whether an employer sign-up came from a person at a
 * browser rather than a script.
 *
 * <p>The widget on airral.com/sign-up hands the browser a one-use token; this
 * asks Cloudflare whether the token is good. Without a secret it is off, so
 * local runs, tests, and production before the keys exist behave as before.
 *
 * <p>A token Cloudflare refuses is refused here. Cloudflare not answering is
 * not the person's fault, so that lets the sign-up through and says so in the
 * log: the review before any job goes public still stands behind it.
 */
@Service
public class TurnstileVerifier {

    static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    static final String REFUSED = "Please complete the check that you're not a robot, then try again.";

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    private final WebClient webClient;
    private final String secret;

    public TurnstileVerifier(WebClient.Builder webClientBuilder,
                             @Value("${airral.auth.turnstile.secret:}") String secret) {
        this.webClient = webClientBuilder.build();
        this.secret = secret == null ? "" : secret.trim();
        if (this.secret.isEmpty()) {
            log.warn("Turnstile is off (no secret set): employer sign-ups are not checked for bots");
        }
    }

    /** A verifier that checks nothing, as when no secret is set. */
    public static TurnstileVerifier off() {
        return new TurnstileVerifier(WebClient.builder(), "");
    }

    public boolean enabled() {
        return !secret.isEmpty();
    }

    /** Completes when the token is a person's, or the check is off; fails with a 400 otherwise. */
    public Mono<Void> check(String token, String remoteAddress) {
        if (!enabled()) {
            return Mono.empty();
        }
        if (token == null || token.isBlank()) {
            return Mono.error(new BadRequestException(REFUSED));
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token.trim());
        if (remoteAddress != null && !remoteAddress.isBlank()) {
            form.add("remoteip", remoteAddress);
        }
        return webClient.post()
                .uri(VERIFY_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(Outcome.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(error -> {
                    // The secret travels in the body, never the URL, so the error is safe to name.
                    log.warn("Turnstile could not be checked ({}); letting the sign-up through",
                            error.getClass().getSimpleName());
                    return Mono.just(new Outcome(true, List.of(), null));
                })
                .flatMap(outcome -> {
                    if (outcome.success()) {
                        return Mono.<Void>empty();
                    }
                    log.info("Turnstile refused a sign-up: {}", outcome.errorCodes());
                    return Mono.<Void>error(new BadRequestException(REFUSED));
                });
    }

    /** Cloudflare's answer: whether the token is good, and why not. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Outcome(boolean success, @JsonProperty("error-codes") List<String> errorCodes, String hostname) {
    }
}
