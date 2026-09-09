package com.airral.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToLongFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.airral.dto.response.VisitorAnalyticsResponse;

import reactor.core.publisher.Flux;
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

    /**
     * How far back a summary may look.
     *
     * <p>The bound is the whole reason the read is cheap. Postgres uses
     * idx_analytics_events_day for a range on created_at and scans the table for
     * anything without one, and the table only grows; on a db-f1-micro whose
     * pool is five connections, one unbounded scan is enough to matter.
     */
    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 90;

    /** Enough rows to see the shape without one busy path crowding out the rest. */
    private static final int TOP_BREAKDOWN_LIMIT = 20;

    private static final int MAX_SIGNUP_EXPORT = 1000;

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
        String normalized = normalizeEmail(email);
        if (normalized == null) {
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
     * What the two tables above can say when someone asks whether anyone came.
     *
     * <p>There was no reader at all, so the only way to answer that was Cloud
     * Console and hand-written SQL. Counts are computed in Postgres rather than
     * by pulling events back and counting them here, and the window totals are
     * folded out of the daily rollup instead of asked for again, so the large
     * table is scanned once per request.
     */
    public Mono<VisitorAnalyticsResponse> summarize(int days) {
        int windowDays = days <= 0 ? DEFAULT_WINDOW_DAYS : Math.min(days, MAX_WINDOW_DAYS);

        // Anchored to midnight UTC rather than to "now minus N days", so the
        // oldest bucket is a whole day rather than a fragment of one, and so the
        // buckets line up with the UTC date baked into the visitor key.
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .minusDays(windowDays - 1L);

        Mono<List<VisitorAnalyticsResponse.DailyCount>> daily = databaseClient.sql("""
                        SELECT (created_at AT TIME ZONE 'UTC')::date AS day,
                               COUNT(*) AS events,
                               COUNT(*) FILTER (WHERE event_name = 'page_view') AS page_views,
                               COUNT(DISTINCT visitor_key) AS visitors
                        FROM analytics_events
                        WHERE created_at >= :since
                        GROUP BY 1
                        ORDER BY 1 DESC
                        """)
                .bind("since", since)
                .map((row, metadata) -> VisitorAnalyticsResponse.DailyCount.builder()
                        .day(row.get("day", LocalDate.class))
                        .events(count(row.get("events", Long.class)))
                        .pageViews(count(row.get("page_views", Long.class)))
                        .uniqueVisitors(count(row.get("visitors", Long.class)))
                        .build())
                .all()
                .collectList();

        Mono<List<VisitorAnalyticsResponse.NamedCount>> byEvent = breakdown("""
                        SELECT event_name AS name, COUNT(*) AS total
                        FROM analytics_events
                        WHERE created_at >= :since
                        GROUP BY event_name
                        ORDER BY total DESC, name
                        LIMIT :limit
                        """, since);

        Mono<List<VisitorAnalyticsResponse.NamedCount>> topPaths = breakdown("""
                        SELECT path AS name, COUNT(*) AS total
                        FROM analytics_events
                        WHERE created_at >= :since AND path IS NOT NULL
                        GROUP BY path
                        ORDER BY total DESC, name
                        LIMIT :limit
                        """, since);

        Mono<List<VisitorAnalyticsResponse.NamedCount>> topReferrers = breakdown("""
                        SELECT referrer_host AS name, COUNT(*) AS total
                        FROM analytics_events
                        WHERE created_at >= :since AND referrer_host IS NOT NULL
                        GROUP BY referrer_host
                        ORDER BY total DESC, name
                        LIMIT :limit
                        """, since);

        // Both signup numbers in one pass. The all-time figure is the one anyone
        // actually asks for, and email_signups is small enough that counting it
        // whole is cheaper than keeping a second index to avoid doing so.
        Mono<SignupCounts> signups = databaseClient.sql("""
                        SELECT COUNT(*) AS total,
                               COUNT(*) FILTER (WHERE created_at >= :since) AS recent
                        FROM email_signups
                        """)
                .bind("since", since)
                .map((row, metadata) -> new SignupCounts(
                        count(row.get("total", Long.class)),
                        count(row.get("recent", Long.class))))
                .one()
                .defaultIfEmpty(new SignupCounts(0L, 0L));

        return Mono.zip(daily, byEvent, topPaths, topReferrers, signups)
                .map(parts -> {
                    List<VisitorAnalyticsResponse.DailyCount> dailyCounts = parts.getT1();
                    SignupCounts emails = parts.getT5();

                    return VisitorAnalyticsResponse.builder()
                            .windowDays(windowDays)
                            .since(since)
                            .totalEvents(sum(dailyCounts, VisitorAnalyticsResponse.DailyCount::getEvents))
                            .pageViews(sum(dailyCounts, VisitorAnalyticsResponse.DailyCount::getPageViews))
                            // Summing the daily figures is not an approximation:
                            // the key already contains the date, so the same
                            // person on two days is two keys, and this equals a
                            // COUNT(DISTINCT) over the whole window.
                            .uniqueVisitorDays(sum(dailyCounts, VisitorAnalyticsResponse.DailyCount::getUniqueVisitors))
                            .emailSignups(emails.total())
                            .emailSignupsInWindow(emails.inWindow())
                            .daily(dailyCounts)
                            .byEvent(parts.getT2())
                            .topPaths(parts.getT3())
                            .topReferrers(parts.getT4())
                            .build();
                });
    }

    /**
     * The captured addresses themselves, newest first.
     *
     * <p>Nothing has ever read this table either, and nothing sends to it --
     * outbound SMTP does not work from Cloud Run here -- so until it does, being
     * able to take the list out is the only thing a capture is worth.
     */
    public Flux<EmailSignup> listEmailSignups(int limit) {
        return databaseClient.sql("""
                        SELECT email, source, referrer_host, created_at
                        FROM email_signups
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .bind("limit", limit <= 0 ? MAX_SIGNUP_EXPORT : Math.min(limit, MAX_SIGNUP_EXPORT))
                .map((row, metadata) -> new EmailSignup(
                        row.get("email", String.class),
                        row.get("source", String.class),
                        row.get("referrer_host", String.class),
                        row.get("created_at", OffsetDateTime.class)))
                .all();
    }

    public record EmailSignup(String email, String source, String referrerHost, OffsetDateTime createdAt) {
    }

    private record SignupCounts(long total, long inWindow) {
    }

    private Mono<List<VisitorAnalyticsResponse.NamedCount>> breakdown(String sql, OffsetDateTime since) {
        return databaseClient.sql(sql)
                .bind("since", since)
                .bind("limit", TOP_BREAKDOWN_LIMIT)
                .map((row, metadata) -> VisitorAnalyticsResponse.NamedCount.builder()
                        .name(row.get("name", String.class))
                        .count(count(row.get("total", Long.class)))
                        .build())
                .all()
                .collectList();
    }

    /** A grouped COUNT is never null; the row accessor is typed as though it could be. */
    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private long sum(List<VisitorAnalyticsResponse.DailyCount> days,
                     ToLongFunction<VisitorAnalyticsResponse.DailyCount> field) {
        return days.stream().mapToLong(field).sum();
    }

    /**
     * The address in the form it will be stored, or null if it is not one.
     *
     * <p>This used to accept anything containing an '@', which is how
     * "audit-probe@", "@" and "a@b" all became subscribers -- a capture list
     * accumulating rows nobody can ever send to. The checks are structural only:
     * a local part, exactly one '@', a domain carrying a dot, and a last label
     * that could be a real TLD. Nothing beyond that, because no pattern short of
     * actually sending mail separates a real address from a well-formed one, and
     * turning away someone's real address is the worse of the two mistakes.
     */
    static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }

        String value = email.trim().toLowerCase(Locale.US);
        // 254 is the longest an address may be in an SMTP envelope; the column
        // holds 255, so this is the tighter of the two limits.
        if (value.length() > 254 || value.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }

        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@')) {
            return null;
        }

        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        if (local.length() > 64) {
            return null;
        }

        // No dot at all ("a@b"), a leading or trailing dot, or an empty label in
        // the middle. Anything else in the domain is left alone. The leading-dot
        // test is its own clause because lastIndexOf only catches it when there
        // is one dot: "a@.com" was refused but "a@.b.com" was not.
        int lastDot = domain.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == domain.length() - 1
                || domain.charAt(0) == '.' || domain.contains("..")) {
            return null;
        }

        return plausibleTld(domain.substring(lastDot + 1)) ? value : null;
    }

    /**
     * Whether a domain's last label could be a real TLD.
     *
     * <p>Letters, since every TLD is one -- except an internationalised domain,
     * which reaches us already punycoded as "xn--p1ai" and would fail a
     * letters-only test on its own digits.
     */
    private static boolean plausibleTld(String tld) {
        if (tld.startsWith("xn--")) {
            return tld.length() > 4;
        }
        return tld.length() >= 2 && tld.codePoints().allMatch(Character::isLetter);
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
