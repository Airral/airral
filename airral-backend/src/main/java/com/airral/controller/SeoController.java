package com.airral.controller;

import com.airral.dto.response.JobResponse;
import com.airral.service.ExternalJobPostingStore;
import com.airral.service.JobService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/seo")
public class SeoController {

    private static final String SITE_URL = "https://www.airral.com";

    /**
     * Sitemaps are capped at 50,000 URLs by the protocol. Staying well under it
     * keeps this a single file; if the catalogue ever outgrows the cap this needs
     * splitting into an index rather than quietly truncating.
     */
    private static final int MAX_SITEMAP_URLS = 45_000;

    private final JobService jobService;
    private final ExternalJobPostingStore externalJobPostingStore;

    public SeoController(JobService jobService, ExternalJobPostingStore externalJobPostingStore) {
        this.jobService = jobService;
        this.externalJobPostingStore = externalJobPostingStore;
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
    @GetMapping(value = "/jobs-sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<String> getJobsSitemap() {
        Mono<java.util.List<String>> internal = jobService.getPublicOpenJobs()
                .map(this::toJobUrl)
                .collectList();

        Mono<java.util.List<String>> external = externalJobPostingStore
                .findSitemapEntries(MAX_SITEMAP_URLS)
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
