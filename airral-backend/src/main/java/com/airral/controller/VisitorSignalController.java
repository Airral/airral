package com.airral.controller;

import java.net.InetSocketAddress;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.airral.service.VisitorSignalService;

import reactor.core.publisher.Mono;

/**
 * The two public writes that make the product legible: a visit happened, and
 * somebody left an address.
 *
 * <p>Same-origin on purpose. A third-party analytics tag would be blocked by a
 * large share of this audience -- the postings are mostly engineering roles --
 * and the resulting numbers would be confidently wrong rather than absent.
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
