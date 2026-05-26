package com.airral.controller;

import com.airral.dto.response.JobResponse;
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
    private final JobService jobService;

    public SeoController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping(value = "/jobs-sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<String> getJobsSitemap() {
        return jobService.getPublicOpenJobs()
                .map(this::toJobUrl)
                .collectList()
                .map(urls -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                        %s
                        </urlset>
                        """.formatted(String.join("", urls)));
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
