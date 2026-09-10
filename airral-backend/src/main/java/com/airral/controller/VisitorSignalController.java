package com.airral.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.airral.config.ClientIpConfig;
import com.airral.dto.response.VisitorAnalyticsResponse;
import com.airral.service.VisitorSignalService;

import reactor.core.publisher.Mono;

/**
 * The two public writes that make the product legible -- a visit happened, and
 * somebody left an address -- and the admin-only reads that make them worth
 * having.
 *
 * <p>Same-origin on purpose. A third-party analytics tag would be blocked by a
 * large share of this audience -- the postings are mostly engineering roles --
 * and the resulting numbers would be confidently wrong rather than absent.
 *
 * <p>The reads sit under /api/admin so that SecurityConfig's existing
 * {@code /api/admin/**} rule covers them without a new allow-list entry. They
 * are in this class rather than a separate admin controller because they are the
 * other half of these two writes, and a reader who wants to know what is counted
 * should not have to find a second file.
 */
@RestController
@RequestMapping("/api")
public class VisitorSignalController {

    private final VisitorSignalService visitorSignalService;

    public VisitorSignalController(VisitorSignalService visitorSignalService) {
        this.visitorSignalService = visitorSignalService;
    }

    /**
     * Records one event. Always answers 204, whatever happened.
     *
     * <p>A beacon fired during someone's visit has no useful failure: the page
     * cannot act on an error and the person did not ask for this. An unrecognised
     * event name is dropped rather than rejected, for the same reason.
     */
    @PostMapping("/events")
    public Mono<ResponseEntity<Void>> record(
            @RequestBody(required = false) Map<String, String> body,
            ServerWebExchange exchange) {

        Map<String, String> payload = body == null ? Map.of() : body;

        return visitorSignalService.record(
                        payload.get("event"),
                        payload.get("path"),
                        firstNonBlank(payload.get("referrer"),
                                exchange.getRequest().getHeaders().getFirst(HttpHeaders.REFERER)),
                        payload.get("app"),
                        clientAddress(exchange),
                        exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT))
                .thenReturn(ResponseEntity.noContent().<Void>build())
                .onErrorReturn(ResponseEntity.noContent().build());
    }

    /**
     * Takes an email address for someone not ready to make an account.
     *
     * <p>Answers the same whether the address is new or already on the list.
     * Saying which would tell a stranger something about somebody else.
     */
    @PostMapping("/email-signups")
    public Mono<ResponseEntity<Map<String, String>>> captureEmail(
            @RequestBody Map<String, String> body,
            ServerWebExchange exchange) {

        return visitorSignalService.captureEmail(
                        body.get("email"),
                        body.getOrDefault("source", "website"),
                        firstNonBlank(body.get("referrer"),
                                exchange.getRequest().getHeaders().getFirst(HttpHeaders.REFERER)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "status", "ok",
                        "message", "Thanks — we'll be in touch.")))
                .onErrorResume(IllegalArgumentException.class, error -> Mono.just(
                        ResponseEntity.badRequest().body(Map.of(
                                "status", "invalid",
                                "message", "That does not look like an email address."))));
    }

    /**
     * How many came, when, and from where.
     *
     * <p>The feature shipped write-only: two POSTs, no GET, so "did anyone come"
     * was answerable only by opening Cloud Console and typing SQL.
     *
     * <p>ADMIN, not public. The writes are deliberately open because they are
     * made by people who are not signed in; this is aggregate business data
     * about the whole product and has no such excuse. The path already falls
     * under SecurityConfig's {@code /api/admin/**} rule -- the annotation is not
     * redundant with it, it is what keeps this shut if that matcher list is ever
     * reordered or narrowed.
     */
    @GetMapping("/admin/analytics/visitors")
    @PreAuthorize("hasAuthority('ADMIN')")
    public Mono<ResponseEntity<VisitorAnalyticsResponse>> summary(
            @RequestParam(value = "days", defaultValue = "30") int days) {

        return visitorSignalService.summarize(days)
                .map(ResponseEntity::ok);
    }

    /**
     * The captured addresses.
     *
     * <p>Its own endpoint rather than a field on the summary: a count of signups
     * is a dashboard number, and the addresses are somebody's personal data, so
     * reading them should be something a person chose to do rather than
     * something that arrives with the totals.
     */
    @GetMapping("/admin/analytics/email-signups")
    @PreAuthorize("hasAuthority('ADMIN')")
    public Mono<ResponseEntity<List<VisitorSignalService.EmailSignup>>> emailSignups(
            @RequestParam(value = "limit", defaultValue = "500") int limit) {

        return visitorSignalService.listEmailSignups(limit)
                .collectList()
                .map(ResponseEntity::ok);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /**
     * The caller's address as seen from outside. Only ever hashed into the
     * salted daily visitor key, never stored.
     *
     * <p>This had its own copy of the sign-in path's left-most X-Forwarded-For
     * parse, and inherited the same defect: Cloud Run appends the real address
     * to a caller-supplied header instead of replacing it, so the left-most
     * entry was whatever the caller wrote and a visitor could be counted as
     * many distinct people. Both copies now defer to {@link ClientIpConfig},
     * which resolves from the trusted right-hand end of the chain in the one
     * place that can still see it.
     */
    private String clientAddress(ServerWebExchange exchange) {
        return ClientIpConfig.clientAddress(exchange);
    }
}
