package com.airral.controller;

import java.net.InetSocketAddress;
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
     * The caller's address as seen from outside.
     *
     * <p>Defensive in the same way the sign-in path had to become: Cloud Run
     * terminates TLS and Spring applies then strips X-Forwarded-For, leaving a
     * remote address that can be unresolved, where getAddress() returns null.
     * Dereferencing that turned every sign-in into a 500 once already.
     *
     * <p>Only ever hashed, never stored.
     */
    private String clientAddress(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }

        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null) {
            if (remote.getAddress() != null) {
                return remote.getAddress().getHostAddress();
            }
            if (remote.getHostString() != null && !remote.getHostString().isBlank()) {
                return remote.getHostString();
            }
        }
        return "unknown";
    }
}
