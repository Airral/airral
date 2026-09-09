package com.airral.controller;

import com.airral.dto.response.JobResponse;
import com.airral.service.ExternalJobPostingStore;
import com.airral.service.JobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Crawler-facing endpoints.
 *
 * <p>The paths are spelled out per method rather than sharing a class-level
 * /api/seo prefix, because robots.txt is only ever fetched from the host root:
 * a crawler asks for https://api.airral.com/robots.txt and nothing else. Under
 * the prefix it would have answered at /api/seo/robots.txt, an address no
 * crawler requests. The sitemap keeps its published /api/seo path.
 */
@RestController
public class SeoController {

    private static final String SITE_URL = "https://www.airral.com";

    /**
     * The API host, spelled out because the Sitemap directive in robots.txt has
     * to be absolute: a crawler resolves it against the host it read robots.txt
     * from, so a relative path would send it back to www.airral.com, where this
     * sitemap does not exist.
     */
    private static final String API_URL = "https://api.airral.com";

    /** Shared by the mapping and by robots.txt so the two cannot drift apart. */
    private static final String JOBS_SITEMAP_PATH = "/api/seo/jobs-sitemap.xml";

    /**
     * Sitemaps are capped at 50,000 URLs by the protocol. Staying well under it
     * keeps this a single file; if the catalogue ever outgrows the cap this needs
     * splitting into an index rather than quietly truncating.
     */
    private static final int MAX_SITEMAP_URLS = 45_000;

    private final JobService jobService;
    private final ExternalJobPostingStore externalJobPostingStore;

    /**
     * The same property every search query resolves its freshness cutoff from,
     * read here so the sitemap cannot advertise a window the site itself has
     * stopped serving.
     */
    private final int maxAgeDays;

    public SeoController(
            JobService jobService,
            ExternalJobPostingStore externalJobPostingStore,
            @Value("${airral.jobs.max-age-days:60}") int maxAgeDays) {
        this.jobService = jobService;
        this.externalJobPostingStore = externalJobPostingStore;
        this.maxAgeDays = maxAgeDays;
    }

    /**
     * The API host's own robots.txt.
     *
     * <p>www.airral.com/robots.txt advertises the jobs sitemap as
     * https://api.airral.com/api/seo/jobs-sitemap.xml, and a crawler will not
     * read a sitemap from a host before it has read that host's robots.txt.
     * api.airral.com served none: the request fell through to the catch-all
     * authenticated() rule and came back 401, so the sitemap was permitted,
     * correct, and still never fetched.
     *
     * <p>Nothing else on this host is worth crawl budget -- it is JSON for the
     * apps, and any of it that reached an index would be a result no reader
     * wants -- so the sitemap path is the only thing allowed. The Allow is
     * written before the Disallow on purpose: RFC 9309 crawlers take the most
     * specific match and older first-match ones take the first, and in this
     * order both read the sitemap as allowed.
     */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> getRobotsTxt() {
        return Mono.just("""
                User-agent: *
                Allow: /api/seo/
                Disallow: /

                Sitemap: %s%s
                """.formatted(API_URL, JOBS_SITEMAP_PATH));
    }

    /**
     * Every job worth indexing, employer-posted and synced alike.
     *
     * <p>This used to list only jobs posted directly by employers through the
     * ATS. There are none, so the sitemap was an empty urlset: a catalogue of
     * more than fifteen thousand postings with not one address a search engine
     * could find. The synced postings are the catalogue, and they are what people
     * search for.
     */
    @GetMapping(value = JOBS_SITEMAP_PATH, produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<String> getJobsSitemap() {
        // Null means no cutoff, which is what the store does with a non-positive
        // window: findRecommendedJobs and findJobsBySkills both gate the
        // source_updated_at predicate on maxAgeDays > 0, so setting
        // AIRRAL_JOBS_MAX_AGE_DAYS to 0 turns the age limit off and search
        // returns the whole catalogue. Clamping to a minimum of one day here
        // instead would have inverted that -- search widest, sitemap down to the
        // last 24 hours -- which is the opposite of tracking what search serves.
        OffsetDateTime freshnessCutoff = maxAgeDays > 0
                ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(maxAgeDays)
                : null;

        Mono<java.util.List<String>> internal = jobService.getPublicOpenJobs()
                .map(this::toJobUrl)
                .collectList();

        Mono<java.util.List<String>> external = externalJobPostingStore
                .findSitemapEntries(MAX_SITEMAP_URLS)
                .filter(entry -> isReachableFromSearch(entry, freshnessCutoff))
                .map(this::toExternalJobUrl)
                .collectList();

        return Mono.zip(internal, external)
                .map(both -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                        %s%s</urlset>
                        """.formatted(String.join("", both.getT1()), String.join("", both.getT2())));
    }

    /**
     * Whether the site's own search can return this posting.
     *
     * <p>The sitemap offered 15,969 URLs against the 15,330 search would return.
     * The store's sitemap query asks only that a posting be active and unexpired,
     * and expiry is measured from last_seen_at -- so a role still appearing on its
     * employer's board never expires, however old its publish date. Every search
     * query additionally requires source_updated_at inside
     * airral.jobs.max-age-days, a predicate that also drops the rows where the
     * board gave no date at all. The 639 in between were advertised to crawlers
     * and findable nowhere on the site: orphans, and stale ones, whose only
     * inbound link would have been the sitemap itself.
     *
     * <p>The filter runs here rather than in SQL, so it reads the same property
     * the search cutoff is built from. Applying it after the query's LIMIT is
     * safe rather than merely cheap: findSitemapEntries orders by
     * source_updated_at DESC NULLS LAST, so every row this drops already sorts
     * behind every row it keeps, and truncating at the cap can only ever remove
     * rows that were going to be dropped anyway.
     *
     * <p>A null cutoff means the age limit is switched off and every active
     * posting is reachable, matching the store's own maxAgeDays > 0 gate.
     */
    private boolean isReachableFromSearch(
            ExternalJobPostingStore.SitemapEntry entry, OffsetDateTime freshnessCutoff) {
        if (freshnessCutoff == null) {
            return true;
        }

        return entry.sourceUpdatedAt() != null
                && !entry.sourceUpdatedAt().isBefore(freshnessCutoff);
    }

    /**
     * The address of a synced posting.
     *
     * <p>Three path segments rather than one opaque key, so the URL reads as
     * something -- /jobs/greenhouse/anthropic/5386949008 -- and so it mirrors the
     * detail endpoint the page behind it calls. Each segment is percent-encoded
     * because some boards put slashes in their identifiers: Workday's external
     * path arrives already containing %2F, and a raw one would silently split the
     * URL into the wrong number of segments.
     */
    private String toExternalJobUrl(ExternalJobPostingStore.SitemapEntry entry) {
        String lastModifiedNode = entry.sourceUpdatedAt() == null
                ? ""
                : "    <lastmod>%s</lastmod>%n".formatted(
                        entry.sourceUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE));

        return """
                  <url>
                    <loc>%s/jobs/%s/%s/%s</loc>
                %s    <changefreq>daily</changefreq>
                    <priority>0.6</priority>
                  </url>
                """.formatted(
                SITE_URL,
                segment(entry.sourceType()),
                segment(entry.boardToken()),
                segment(entry.externalJobId()),
                lastModifiedNode);
    }

    /** One path segment, safe to sit between slashes. */
    private String segment(String value) {
        return value == null
                ? ""
                : java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
    }

    private String toJobUrl(JobResponse job) {
        String lastModified = job.getUpdatedAt() != null
                ? job.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : "";
        String lastModifiedNode = lastModified.isBlank()
                ? ""
                : "    <lastmod>%s</lastmod>%n".formatted(escape(lastModified));
        return """
                          <url>
                            <loc>%s/jobs/%s</loc>
                        %s    <changefreq>daily</changefreq>
                            <priority>0.8</priority>
                          </url>
                        """.formatted(SITE_URL, job.getId(), lastModifiedNode);
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
