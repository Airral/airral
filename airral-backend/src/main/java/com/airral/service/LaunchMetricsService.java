package com.airral.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * Whether launch is working: did people come, sign up, and get as far as a job.
 *
 * <p>Built from what the product already stores -- accounts, resumes, fit
 * results, saved jobs -- plus the one event that has nowhere else to live, a
 * signed-in applicant clicking "Apply now". Traffic is anonymous visitor-days
 * and cannot be joined to accounts; the funnel is people, and is.
 *
 * <p>Test accounts are left out by address, so the numbers are about real
 * people. Days are UTC, like the visitor key.
 */
@Service
public class LaunchMetricsService {

    static final int MAX_WINDOW_DAYS = 90;
    private static final int RECENT_APPLICANTS = 30;
    private static final int TOP_REFERRERS = 10;

    /**
     * Addresses that are never a real person: the verification gate and manual
     * testing sign these up. Anything here is excluded from every count below.
     */
    static final String REAL_ACCOUNT = """
            lower(split_part(u.email, '@', 2)) NOT IN
                ('example.com', 'example.org', 'example.net', 'local.test', 'test.com')""";

    private final DatabaseClient databaseClient;

    public LaunchMetricsService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public record Traffic(long visitorDays, long applicantVisitorDays, long websiteVisitorDays,
                          long pageViews, long applyClicks) {}

    /** People who signed up in the window, and how many of them reached each step since. */
    public record Funnel(long signedUp, long verified, long uploadedResume, long ranMatch,
                         long savedJob, long clickedApply, long trackedApplied, long testAccountsHidden) {}

    public record Employers(long signedUp, long companiesWaitingForReview, long verifiedCompanies,
                            long openEmployerJobs) {}

    public record Day(LocalDate day, long visitors, long signups, long applyClicks) {}

    public record Applicant(Long id, String email, String name, LocalDateTime signedUpAt,
                            LocalDateTime lastLoginAt, OffsetDateTime lastSeenAt, boolean verified,
                            long resumes, long matches, long savedJobs, long applyClicks) {}

    public record Referrer(String host, long visits) {}

    public record LaunchMetrics(int windowDays, OffsetDateTime since, Traffic traffic, Funnel funnel,
                                Employers employers, List<Day> daily, List<Applicant> recentApplicants,
                                List<Referrer> topReferrers) {}

    public Mono<LaunchMetrics> summarize(int days) {
        int windowDays = Math.max(1, Math.min(days, MAX_WINDOW_DAYS));
        // Midnight UTC, so the first day is whole. users.created_at is a plain
        // TIMESTAMP written in the database's zone, which is UTC on Cloud SQL.
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .minusDays(windowDays - 1L);
        LocalDateTime sinceLocal = since.toLocalDateTime();

        Mono<Traffic> traffic = databaseClient.sql("""
                        SELECT COUNT(DISTINCT visitor_key) AS visitors,
                               COUNT(DISTINCT visitor_key) FILTER (WHERE app = 'applicant') AS applicant_visitors,
                               COUNT(DISTINCT visitor_key) FILTER (WHERE app = 'website') AS website_visitors,
                               COUNT(*) FILTER (WHERE event_name = 'page_view') AS page_views,
                               COUNT(*) FILTER (WHERE event_name = 'apply_click') AS apply_clicks
                        FROM analytics_events
                        WHERE created_at >= :since
                        """)
                .bind("since", since)
                .map((row, meta) -> new Traffic(n(row, "visitors"), n(row, "applicant_visitors"),
                        n(row, "website_visitors"), n(row, "page_views"), n(row, "apply_clicks")))
                .one();

        Mono<Funnel> funnel = databaseClient.sql("""
                        WITH cohort AS (
                            SELECT u.id, COALESCE(u.email_verified, false) AS verified
                            FROM users u
                            WHERE u.role = 'APPLICANT' AND u.created_at >= :since AND %s
                        )
                        SELECT COUNT(*) AS signed_up,
                               COUNT(*) FILTER (WHERE c.verified) AS verified,
                               COUNT(*) FILTER (WHERE EXISTS (
                                   SELECT 1 FROM candidate_resume_documents r WHERE r.user_id = c.id)) AS resume,
                               COUNT(*) FILTER (WHERE EXISTS (
                                   SELECT 1 FROM candidate_job_fit_results f WHERE f.user_id = c.id)) AS matched,
                               COUNT(*) FILTER (WHERE EXISTS (
                                   SELECT 1 FROM candidate_saved_jobs s WHERE s.user_id = c.id)) AS saved,
                               COUNT(*) FILTER (WHERE EXISTS (
                                   SELECT 1 FROM analytics_events e
                                   WHERE e.user_id = c.id AND e.event_name = 'apply_click')) AS clicked_apply,
                               COUNT(*) FILTER (WHERE EXISTS (
                                   SELECT 1 FROM candidate_saved_jobs s WHERE s.user_id = c.id
                                   AND s.status IN ('APPLIED', 'INTERVIEWING', 'OFFER', 'REJECTED'))) AS tracked_applied,
                               (SELECT COUNT(*) FROM users u
                                 WHERE u.role = 'APPLICANT' AND u.created_at >= :since
                                   AND NOT (%s)) AS test_hidden
                        FROM cohort c
                        """.formatted(REAL_ACCOUNT, REAL_ACCOUNT))
                .bind("since", sinceLocal)
                .map((row, meta) -> new Funnel(n(row, "signed_up"), n(row, "verified"), n(row, "resume"),
                        n(row, "matched"), n(row, "saved"), n(row, "clicked_apply"),
                        n(row, "tracked_applied"), n(row, "test_hidden")))
                .one();

        Mono<Employers> employers = databaseClient.sql("""
                        SELECT (SELECT COUNT(*) FROM users u
                                 WHERE u.role = 'HR_MANAGER' AND u.organization_id IS NOT NULL
                                   AND u.created_at >= :since AND %s) AS signed_up,
                               (SELECT COUNT(*) FROM organizations
                                 WHERE verification_status = 'PENDING') AS pending,
                               (SELECT COUNT(*) FROM organizations
                                 WHERE verification_status = 'VERIFIED') AS verified,
                               (SELECT COUNT(*) FROM jobs WHERE status = 'OPEN') AS open_jobs
                        """.formatted(REAL_ACCOUNT))
                .bind("since", sinceLocal)
                .map((row, meta) -> new Employers(n(row, "signed_up"), n(row, "pending"),
                        n(row, "verified"), n(row, "open_jobs")))
                .one();

        Mono<List<Day>> daily = databaseClient.sql("""
                        WITH days AS (
                            SELECT generate_series(:first, (now() AT TIME ZONE 'UTC')::date, interval '1 day')::date AS day
                        ),
                        visits AS (
                            SELECT (created_at AT TIME ZONE 'UTC')::date AS day,
                                   COUNT(DISTINCT visitor_key) AS visitors,
                                   COUNT(*) FILTER (WHERE event_name = 'apply_click') AS apply_clicks
                            FROM analytics_events
                            WHERE created_at >= :since
                            GROUP BY 1
                        ),
                        signups AS (
                            SELECT u.created_at::date AS day, COUNT(*) AS signups
                            FROM users u
                            WHERE u.role = 'APPLICANT' AND u.created_at >= :sinceLocal AND %s
                            GROUP BY 1
                        )
                        SELECT d.day,
                               COALESCE(v.visitors, 0) AS visitors,
                               COALESCE(s.signups, 0) AS signups,
                               COALESCE(v.apply_clicks, 0) AS apply_clicks
                        FROM days d
                        LEFT JOIN visits v ON v.day = d.day
                        LEFT JOIN signups s ON s.day = d.day
                        ORDER BY d.day
                        """.formatted(REAL_ACCOUNT))
                .bind("first", since.toLocalDate())
                .bind("since", since)
                .bind("sinceLocal", sinceLocal)
                .map((row, meta) -> new Day(row.get("day", LocalDate.class), n(row, "visitors"),
                        n(row, "signups"), n(row, "apply_clicks")))
                .all()
                .collectList();

        Mono<List<Applicant>> recent = databaseClient.sql("""
                        SELECT u.id, u.email, u.first_name, u.last_name, u.created_at, u.last_login_at,
                               COALESCE(u.email_verified, false) AS verified,
                               (SELECT COUNT(*) FROM candidate_resume_documents r WHERE r.user_id = u.id) AS resumes,
                               (SELECT COUNT(*) FROM candidate_job_fit_results f WHERE f.user_id = u.id) AS matches,
                               (SELECT COUNT(*) FROM candidate_saved_jobs s WHERE s.user_id = u.id) AS saved,
                               (SELECT COUNT(*) FROM analytics_events e
                                 WHERE e.user_id = u.id AND e.event_name = 'apply_click') AS apply_clicks,
                               (SELECT MAX(e.created_at) FROM analytics_events e WHERE e.user_id = u.id) AS last_seen
                        FROM users u
                        WHERE u.role = 'APPLICANT' AND u.created_at >= :since AND %s
                        ORDER BY u.created_at DESC
                        LIMIT :limit
                        """.formatted(REAL_ACCOUNT))
                .bind("since", sinceLocal)
                .bind("limit", RECENT_APPLICANTS)
                .map((row, meta) -> new Applicant(
                        row.get("id", Long.class),
                        row.get("email", String.class),
                        name(row.get("first_name", String.class), row.get("last_name", String.class)),
                        row.get("created_at", LocalDateTime.class),
                        row.get("last_login_at", LocalDateTime.class),
                        row.get("last_seen", OffsetDateTime.class),
                        Boolean.TRUE.equals(row.get("verified", Boolean.class)),
                        n(row, "resumes"), n(row, "matches"), n(row, "saved"), n(row, "apply_clicks")))
                .all()
                .collectList();

        Mono<List<Referrer>> referrers = databaseClient.sql("""
                        SELECT referrer_host AS host, COUNT(DISTINCT visitor_key) AS visits
                        FROM analytics_events
                        WHERE created_at >= :since AND referrer_host IS NOT NULL
                          -- Moving between our own sites, or a developer's
                          -- machine, is not somebody finding AIRRAL.
                          AND referrer_host <> 'airral.com' AND referrer_host NOT LIKE '%.airral.com'
                          AND referrer_host NOT IN ('localhost', '127.0.0.1')
                        GROUP BY referrer_host
                        ORDER BY visits DESC, host
                        LIMIT :limit
                        """)
                .bind("since", since)
                .bind("limit", TOP_REFERRERS)
                .map((row, meta) -> new Referrer(row.get("host", String.class), n(row, "visits")))
                .all()
                .collectList();

        return Mono.zip(traffic, funnel, employers, daily, recent, referrers)
                .map(parts -> new LaunchMetrics(windowDays, since, parts.getT1(), parts.getT2(),
                        parts.getT3(), parts.getT4(), parts.getT5(), parts.getT6()));
    }

    private static long n(Readable row, String column) {
        Number value = row.get(column, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static String name(String first, String last) {
        String joined = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return joined.isEmpty() ? null : joined;
    }
}
