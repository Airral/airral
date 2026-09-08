package com.airral.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Counting visitors, and keeping an address for the ones who are not ready to
 * make an account.
 *
 * <p>The product had neither. Nothing measured a visit, so "did anyone come" was
 * unanswerable except by reading request logs, and anyone who looked around
 * without signing up left no trace at all.
 */
@Service
public class VisitorSignalService {

    private static final Logger log = LoggerFactory.getLogger(VisitorSignalService.class);

    /**
     * Names this endpoint will record. An allowlist rather than free text
     * because the write is public: without one, anyone could fill the table with
     * whatever they liked, and the counts would stop meaning anything.
     */
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "page_view", "job_search", "job_view", "apply_click",
            "signup_start", "signup_complete", "email_capture", "resume_upload");

    private final DatabaseClient databaseClient;
    private final String salt;

    public VisitorSignalService(
            DatabaseClient databaseClient,
            @Value("${airral.analytics.visitor-salt:airral-visitor}") String salt) {
        this.databaseClient = databaseClient;
        this.salt = salt;
    }

    /**
     * Records one event.
     *
     * <p>Never fails the caller. This is a page-view beacon fired from a browser
     * during a real person's visit; a problem writing a statistic must not turn
     * into an error on their screen, and there is nothing they could do about it
     * if it did.
     */
    public Mono<Void> record(String eventName, String path, String referrer,
                             String app, String address, String userAgent) {
        String name = eventName == null ? "" : eventName.trim().toLowerCase(Locale.US);
        if (!ALLOWED_EVENTS.contains(name)) {
            return Mono.empty();
        }

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO analytics_events
                            (event_name, path, referrer_host, visitor_key, app)
                        VALUES (:name, :path, :referrer, :visitor, :app)
                        """)
                .bind("name", name)
                .bind("visitor", visitorKey(address, userAgent));

        spec = bindText(spec, "path", truncate(path, 500));
        spec = bindText(spec, "referrer", truncate(referrerHost(referrer), 255));
        spec = bindText(spec, "app", truncate(app, 40));

        return spec.fetch()
                .rowsUpdated()
                .doOnError(error -> log.warn("Could not record {}: {}", name, error.getMessage()))
                .onErrorResume(error -> Mono.just(0L))
                .then();
    }

    /**
     * Stores an address, or quietly does nothing if it is already there.
     *
     * <p>Answering the same either way: telling a stranger whether an address is
     * already on the list is an answer about somebody else.
     */
    public Mono<Void> captureEmail(String email, String source, String referrer) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty() || !normalized.contains("@") || normalized.length() > 255) {
            return Mono.error(new IllegalArgumentException("A valid email address is required"));
        }

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO email_signups (email, source, referrer_host)
                        VALUES (:email, :source, :referrer)
                        ON CONFLICT (email) DO NOTHING
                        """)
                .bind("email", normalized);

        spec = bindText(spec, "source", truncate(source, 60));
        spec = bindText(spec, "referrer", truncate(referrerHost(referrer), 255));

        return spec.fetch()
                .rowsUpdated()
                .then();
    }

    /**
     * A day of one person, without knowing who they are.
     *
     * <p>The date is in the hash, so the same person counts once today and is
     * unrecognisable tomorrow. That is the point: it answers "how many people
     * came today" without building anything that could follow someone around.
     * No address is stored, and nothing is written to their browser.
     */
    private String visitorKey(String address, String userAgent) {
        String material = salt + "|" + LocalDate.now(ZoneOffset.UTC)
                + "|" + (address == null ? "" : address)
                + "|" + (userAgent == null ? "" : userAgent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    /**
     * Binds an optional column.
     *
     * <p>R2DBC refuses a null in bind() and insists on bindNull, which is a sharp
     * edge for exactly the columns most likely to be absent: most visits carry no
     * referrer at all. Left unhandled, every capture answered 400.
     */
    private DatabaseClient.GenericExecuteSpec bindText(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    /** Host only -- a referring URL's query string is the visitor's business. */
    private String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(referrer.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.US);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
