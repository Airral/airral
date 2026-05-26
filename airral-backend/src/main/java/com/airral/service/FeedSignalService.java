package com.airral.service;

import com.airral.dto.response.FeedSignalPageResponse;
import com.airral.dto.response.FeedSignalResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class FeedSignalService {
    private static final int MAX_SIGNAL_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final DateTimeFormatter GDELT_SEEN_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US);
    private static final List<String> DEFAULT_SIGNAL_QUERIES = List.of(
            "(\"raised\" OR \"raises\" OR \"funding\" OR \"series a\" OR \"series b\" OR \"seed round\") (software OR SaaS OR AI OR startup)",
            "(\"Y Combinator\" OR \"YC-backed\") (raises OR raised OR funding OR launches OR hiring)",
            "(\"a16z\" OR \"Andreessen Horowitz\") (leads OR invests OR funding OR startup)",
            "(Google OR Microsoft OR Amazon OR Meta OR Apple OR OpenAI OR Anthropic OR Nvidia) (hiring OR layoffs OR launches OR acquisition OR partnership)"
    );

    private final WebClient webClient;
    private final boolean gdeltEnabled;
    private final String defaultQuery;
    private final Duration timeout;
    private final String timespan;

    public FeedSignalService(
            WebClient.Builder webClientBuilder,
            @Value("${airral.feed.signals.gdelt.enabled:true}") boolean gdeltEnabled,
            @Value("${airral.feed.signals.gdelt.base-url:https://api.gdeltproject.org}") String baseUrl,
            @Value("${airral.feed.signals.gdelt.default-query:}") String defaultQuery,
            @Value("${airral.feed.signals.gdelt.timespan:30d}") String timespan,
            @Value("${airral.feed.signals.gdelt.timeout-ms:5000}") long timeoutMs) {
        this.gdeltEnabled = gdeltEnabled;
        this.defaultQuery = defaultQuery;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.timespan = timespan;
        this.webClient = webClientBuilder.clone()
                .baseUrl(baseUrl)
                .exchangeStrategies(signalExchangeStrategies())
                .build();
    }

    public Mono<FeedSignalPageResponse> getSignals(String query, int size) {
        int safeSize = Math.max(1, Math.min(size, 30));
        List<String> queries = resolveQueries(query);
        String queryLabel = String.join(" || ", queries);

        if (!gdeltEnabled) {
            return Mono.just(emptyPage(queryLabel, safeSize));
        }

        int perQuerySize = Math.max(6, Math.min(12, safeSize));
        return Flux.fromIterable(queries)
                .flatMap(feedQuery -> fetchSignals(feedQuery, perQuerySize), Math.min(queries.size(), 4))
                .collectList()
                .map(signalGroups -> mergeSignals(signalGroups, queryLabel, safeSize))
                .timeout(timeout.plusSeconds(2))
                .onErrorResume(error -> Mono.just(emptyPage(queryLabel, safeSize)));
    }

    private Mono<List<FeedSignalResponse>> fetchSignals(String query, int size) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v2/doc/doc")
                        .queryParam("query", query)
                        .queryParam("mode", "ArtList")
                        .queryParam("format", "json")
                        .queryParam("sort", "hybridrel")
                        .queryParam("timespan", normalizeTimespan())
                        .queryParam("maxrecords", size)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(
                        new IllegalStateException("GDELT signal feed returned " + response.statusCode())))
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(this::toSignals)
                .onErrorResume(error -> Mono.just(List.of()));
    }

    private List<FeedSignalResponse> toSignals(JsonNode root) {
        List<FeedSignalResponse> items = new ArrayList<>();
        JsonNode articles = root.path("articles");

        if (articles.isArray()) {
            articles.forEach(article -> {
                FeedSignalResponse signal = toSignal(article);
                if (signal != null) {
                    items.add(signal);
                }
            });
        }

        return items;
    }

    private FeedSignalPageResponse mergeSignals(List<List<FeedSignalResponse>> signalGroups, String query, int pageSize) {
        Map<String, FeedSignalResponse> uniqueSignals = new LinkedHashMap<>();
        signalGroups.stream()
                .flatMap(List::stream)
                .filter(signal -> signal.getSourceUrl() != null && !signal.getSourceUrl().isBlank())
                .forEach(signal -> uniqueSignals.putIfAbsent(signal.getSourceUrl(), signal));

        List<FeedSignalResponse> items = uniqueSignals.values().stream()
                .sorted(Comparator
                        .comparing(FeedSignalResponse::getPublishedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .limit(pageSize)
                .toList();

        return FeedSignalPageResponse.builder()
                .items(items)
                .page(1)
                .pageSize(pageSize)
                .totalItems(items.size())
                .hasNext(false)
                .provider("GDELT")
                .query(query)
                .build();
    }

    private FeedSignalResponse toSignal(JsonNode article) {
        String headline = text(article, "title");
        String url = text(article, "url");

        if (headline == null || url == null) {
            return null;
        }

        String domain = text(article, "domain");
        String signalType = classifySignal(headline);
        String companyName = extractCompanyName(headline, domain);
        String sourceName = readableDomain(domain);

        return FeedSignalResponse.builder()
                .id(Integer.toUnsignedString(Objects.hash(url, headline)))
                .signalType(signalType)
                .companyName(companyName)
                .headline(headline)
                .summary(summaryFor(signalType, companyName, sourceName))
                .whyItMatters(whyItMatters(signalType))
                .sourceName(sourceName)
                .sourceDomain(domain)
                .sourceUrl(url)
                .sourceImageUrl(text(article, "socialimage"))
                .publishedAt(parseSeenDate(text(article, "seendate")))
                .confidence("COMPANY_SIGNAL".equals(signalType) ? "MEDIUM" : "HIGH")
                .linkedJobsCount(0)
                .tags(List.of(signalType.toLowerCase(Locale.US).replace('_', ' '), "company signal"))
                .primaryAction("View jobs")
                .build();
    }

    private List<String> resolveQueries(String query) {
        if (query != null && !query.isBlank()) {
            return List.of(query.trim());
        }

        if (defaultQuery != null && !defaultQuery.isBlank()) {
            List<String> configuredQueries = List.of(defaultQuery.split("\\|\\|")).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (!configuredQueries.isEmpty()) {
                return configuredQueries;
            }
        }

        return DEFAULT_SIGNAL_QUERIES;
    }

    private String normalizeTimespan() {
        return timespan == null || timespan.isBlank() ? "30d" : timespan.trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isBlank() ? null : text;
    }

    private String classifySignal(String headline) {
        String lower = headline.toLowerCase(Locale.US);
        if (containsAny(lower, "funding", "raised", "raises", "series a", "series b", "series c", "seed round", "venture", "ipo")) {
            return "FUNDING";
        }
        if (containsAny(lower, "hiring", "jobs", "recruiting", "expands team", "headcount", "opens office")) {
            return "HIRING";
        }
        if (containsAny(lower, "launches", "unveils", "new product", "platform", "beta", "release", "rolls out")) {
            return "PRODUCT_LAUNCH";
        }
        if (containsAny(lower, "acquires", "acquisition", "merger", "bought")) {
            return "ACQUISITION";
        }
        if (containsAny(lower, "layoff", "layoffs", "cuts jobs", "bankruptcy", "shutdown", "breach", "lawsuit")) {
            return "RISK";
        }
        return "COMPANY_SIGNAL";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String extractCompanyName(String headline, String domain) {
        String cleaned = headline.replaceAll("^[^:]{2,40}:\\s+", "").trim();
        String lower = cleaned.toLowerCase(Locale.US);
        for (String splitter : List.of(" raises ", " raised ", " launches ", " acquires ", " partners ", " hires ")) {
            int index = lower.indexOf(splitter);
            if (index > 1) {
                return cleaned.substring(0, Math.min(index, 70)).trim();
            }
        }
        return readableDomain(domain);
    }

    private String readableDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return "Company";
        }

        try {
            String host = URI.create(domain.startsWith("http") ? domain : "https://" + domain).getHost();
            String normalized = host == null ? domain : host;
            String label = normalized.replaceFirst("^www\\.", "").split("\\.")[0].replace("-", " ");
            return label.isBlank() ? normalized : Character.toUpperCase(label.charAt(0)) + label.substring(1);
        } catch (Exception ignored) {
            return domain;
        }
    }

    private LocalDateTime parseSeenDate(String seenDate) {
        if (seenDate == null) {
            return LocalDateTime.now();
        }

        try {
            return LocalDateTime.parse(seenDate, GDELT_SEEN_DATE_FORMAT);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    private String summaryFor(String signalType, String companyName, String sourceName) {
        return switch (signalType) {
            case "FUNDING" -> companyName + " has a funding signal from " + sourceName + ".";
            case "HIRING" -> companyName + " has a hiring signal from " + sourceName + ".";
            case "PRODUCT_LAUNCH" -> companyName + " has a product launch signal from " + sourceName + ".";
            case "RISK" -> companyName + " has a risk signal from " + sourceName + ".";
            default -> companyName + " has company news from " + sourceName + ".";
        };
    }

    private String whyItMatters(String signalType) {
        return switch (signalType) {
            case "FUNDING" -> "Funding can create hiring waves and new teams to watch.";
            case "HIRING" -> "Hiring news can help you time applications earlier.";
            case "PRODUCT_LAUNCH" -> "Launches often create demand for new roles.";
            case "RISK" -> "Risk signals help you avoid wasting application time.";
            default -> "Company news gives context before you apply.";
        };
    }

    private FeedSignalPageResponse emptyPage(String query, int pageSize) {
        return FeedSignalPageResponse.builder()
                .items(List.of())
                .page(1)
                .pageSize(pageSize)
                .totalItems(0)
                .hasNext(false)
                .provider("GDELT")
                .query(query)
                .build();
    }

    private ExchangeStrategies signalExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_SIGNAL_RESPONSE_BYTES))
                .build();
    }
}
