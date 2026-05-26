package com.airral.service;

import com.airral.dto.response.NewsArticleResponse;
import com.airral.dto.response.NewsPageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NewsFeedService {
    private static final int MAX_NEWS_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMillis(2500);
    private static final Duration FAST_RESPONSE_BUDGET = Duration.ofMillis(2400);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration EMPTY_CACHE_TTL = Duration.ofSeconds(30);
    private static final int MAX_QUERY_LENGTH = 140;
    private static final String ENGINE_PROVIDER = "AIRRAL_NEWS_ENGINE";
    private static final String ENGINE_VERSION = "airral-news-engine-v1";
    private static final DateTimeFormatter GDELT_SEEN_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US);

    private static final Map<String, String> GDELT_QUERY_BY_CATEGORY = Map.of(
            "TECH", "(software OR SaaS OR AI OR startup) (funding OR hiring OR launch OR partnership OR acquisition)",
            "FUNDING", "(raised OR raises OR funding OR \"series a\" OR \"series b\" OR \"seed round\") (software OR SaaS OR AI OR startup)",
            "YC", "(\"Y Combinator\" OR \"YC-backed\") (raises OR raised OR funding OR launches OR hiring)",
            "A16Z", "(a16z OR \"Andreessen Horowitz\") (leads OR invests OR funding OR startup OR AI)",
            "MAJOR_COMPANIES", "(Google OR Microsoft OR Amazon OR Meta OR Apple OR OpenAI OR Anthropic OR Nvidia) (hiring OR layoffs OR launch OR acquisition OR partnership)",
            "COMPANY", "(software OR SaaS OR AI OR startup OR technology) (hiring OR funding OR launch OR partnership OR acquisition)"
    );

    private static final Map<String, List<NewsSource>> RSS_SOURCES_BY_CATEGORY = Map.of(
            "TECH", List.of(
                    google("Google News tech", "software startup funding OR AI startup hiring OR SaaS company launch"),
                    rss("TechCrunch", "https://techcrunch.com/feed/"),
                    rss("VentureBeat", "https://venturebeat.com/feed/"),
                    rss("Crunchbase News", "https://news.crunchbase.com/feed/")
            ),
            "FUNDING", List.of(
                    google("Google News funding", "software startup raised funding OR AI startup seed funding OR SaaS startup funding round"),
                    rss("Crunchbase News", "https://news.crunchbase.com/feed/"),
                    rss("TechCrunch Startups", "https://techcrunch.com/category/startups/feed/"),
                    rss("TechCrunch Venture", "https://techcrunch.com/category/venture/feed/")
            ),
            "YC", List.of(
                    google("Google News YC", "Y Combinator startup funding OR YC-backed startup launch OR YC startup hiring"),
                    rss("Y Combinator", "https://www.ycombinator.com/blog/rss")
            ),
            "A16Z", List.of(
                    google("Google News a16z", "a16z startup funding OR Andreessen Horowitz investment OR a16z AI"),
                    rss("a16z", "https://a16z.com/feed/")
            ),
            "MAJOR_COMPANIES", List.of(
                    google("Google News major companies", "Google Microsoft Amazon Meta Apple OpenAI Anthropic Nvidia hiring layoffs launches"),
                    rss("The Verge", "https://www.theverge.com/rss/index.xml"),
                    rss("VentureBeat", "https://venturebeat.com/feed/"),
                    rss("TechCrunch", "https://techcrunch.com/feed/")
            ),
            "COMPANY", List.of(
                    google("Google News company", "software company hiring funding launch partnership"),
                    rss("TechCrunch", "https://techcrunch.com/feed/"),
                    rss("Crunchbase News", "https://news.crunchbase.com/feed/")
            )
    );

    private final WebClient webClient;
    private final boolean gdeltEnabled;
    private final String gdeltBaseUrl;
    private final String gdeltTimespan;
    private final Duration requestTimeout;
    private final Map<String, CachedNewsPage> responseCache = new ConcurrentHashMap<>();

    public NewsFeedService(
            WebClient.Builder webClientBuilder,
            @Value("${airral.feed.signals.gdelt.enabled:true}") boolean gdeltEnabled,
            @Value("${airral.feed.signals.gdelt.base-url:https://api.gdeltproject.org}") String gdeltBaseUrl,
            @Value("${airral.feed.signals.gdelt.timespan:30d}") String gdeltTimespan,
            @Value("${airral.feed.signals.gdelt.timeout-ms:5000}") long timeoutMs) {
        this.gdeltEnabled = gdeltEnabled;
        this.gdeltBaseUrl = normalizeBaseUrl(gdeltBaseUrl);
        this.gdeltTimespan = gdeltTimespan == null || gdeltTimespan.isBlank() ? "30d" : gdeltTimespan.trim();
        this.requestTimeout = timeoutMs > 0 ? Duration.ofMillis(Math.max(800, timeoutMs)) : DEFAULT_REQUEST_TIMEOUT;
        this.webClient = webClientBuilder.clone()
                .exchangeStrategies(newsExchangeStrategies())
                .build();
    }

    public Mono<NewsPageResponse> getNews(String category, String query, int size) {
        String normalizedCategory = normalizeCategory(category);
        String safeQuery = sanitizeQuery(query);
        int safeSize = Math.max(1, Math.min(size, 30));
        String cacheKey = cacheKey(normalizedCategory, safeQuery, safeSize);
        NewsPageResponse cachedPage = cachedPage(cacheKey);
        if (cachedPage != null) {
            return Mono.just(cachedPage);
        }

        List<NewsSource> sources = resolveSources(normalizedCategory, safeQuery, safeSize);
        return Flux.fromIterable(sources)
                .flatMap(source -> fetchSource(source, normalizedCategory, safeQuery), Math.min(6, sources.size()))
                .take(FAST_RESPONSE_BUDGET)
                .collectList()
                .map(groups -> buildPage(groups, normalizedCategory, safeQuery, sources, safeSize))
                .map(page -> cachePage(cacheKey, page))
                .timeout(FAST_RESPONSE_BUDGET.plusMillis(800))
                .onErrorResume(error -> Mono.just(cachePage(cacheKey, emptyPage(normalizedCategory, safeQuery, sources, safeSize))));
    }

    private Mono<List<NewsArticleResponse>> fetchSource(NewsSource source, String category, String query) {
        return switch (source.kind()) {
            case GDELT -> fetchGdeltSource(source, category, query);
            case RSS, GOOGLE_NEWS_RSS -> fetchXmlSource(source, category, query);
        };
    }

    private Mono<List<NewsArticleResponse>> fetchGdeltSource(NewsSource source, String category, String query) {
        return webClient.get()
                .uri(URI.create(source.url()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(
                        new IllegalStateException(source.name() + " returned " + response.statusCode())))
                .bodyToMono(JsonNode.class)
                .timeout(requestTimeout)
                .map(root -> parseGdeltArticles(root, source, category, query))
                .onErrorResume(error -> Mono.just(List.of()));
    }

    private Mono<List<NewsArticleResponse>> fetchXmlSource(NewsSource source, String category, String query) {
        return webClient.get()
                .uri(URI.create(source.url()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(
                        new IllegalStateException(source.name() + " returned " + response.statusCode())))
                .bodyToMono(String.class)
                .timeout(requestTimeout)
                .map(xml -> parseArticles(xml, source, category, query))
                .onErrorResume(error -> Mono.just(List.of()));
    }

    private List<NewsArticleResponse> parseGdeltArticles(JsonNode root, NewsSource source, String category, String query) {
        List<NewsArticleResponse> articles = new ArrayList<>();
        JsonNode nodes = root.path("articles");
        if (!nodes.isArray()) {
            return articles;
        }

        nodes.forEach(node -> addIfRelevant(articles, toGdeltArticle(node, source, category, query), query));
        return articles;
    }

    private NewsArticleResponse toGdeltArticle(JsonNode node, NewsSource source, String category, String query) {
        String title = text(node, "title");
        String url = text(node, "url");
        if (title == null || url == null) {
            return null;
        }

        String domain = firstNonBlank(text(node, "domain"), domainFromUrl(url));
        String sourceName = readableDomain(domain);
        return article(
                source,
                category,
                title,
                null,
                url,
                sourceName,
                domain,
                homepageFromDomain(domain),
                firstNonBlank(text(node, "seendate"), text(node, "seenDate")),
                text(node, "socialimage"),
                null,
                firstNonBlank(text(node, "sourcecountry"), text(node, "sourceCountry")),
                text(node, "language"),
                query
        );
    }

    private List<NewsArticleResponse> parseArticles(String xml, NewsSource source, String category, String query) {
        List<NewsArticleResponse> articles = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return articles;
        }

        try {
            Document document = safeDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));

            NodeList items = document.getElementsByTagName("item");
            for (int index = 0; index < items.getLength(); index++) {
                if (items.item(index) instanceof Element item) {
                    addIfRelevant(articles, toRssArticle(item, source, category, query), query);
                }
            }

            NodeList entries = document.getElementsByTagName("entry");
            for (int index = 0; index < entries.getLength(); index++) {
                if (entries.item(index) instanceof Element entry) {
                    addIfRelevant(articles, toAtomArticle(entry, source, category, query), query);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }

        return articles;
    }

    private void addIfRelevant(List<NewsArticleResponse> articles, NewsArticleResponse article, String query) {
        if (article == null) {
            return;
        }

        if (query == null || query.isBlank() || containsQuery(article, query)) {
            articles.add(article);
        }
    }

    private NewsArticleResponse toRssArticle(Element item, NewsSource source, String category, String query) {
        String title = firstNonBlank(childText(item, "title"));
        String url = firstNonBlank(childText(item, "link"), childText(item, "guid"));
        if (title == null || url == null) {
            return null;
        }

        Element sourceElement = firstChild(item, "source");
        String sourceName = firstNonBlank(
                sourceElement == null ? null : cleanText(sourceElement.getTextContent()),
                source.name()
        );
        String sourceHomeUrl = firstNonBlank(
                sourceElement == null ? null : cleanText(sourceElement.getAttribute("url")),
                homepageFromDomain(domainFromUrl(source.url()))
        );
        String sourceDomain = domainFromUrl(firstNonBlank(sourceHomeUrl, url));
        String summary = firstNonBlank(
                childText(item, "description"),
                childText(item, "content:encoded"),
                childText(item, "summary")
        );

        return article(source, category, title, summary, url, sourceName, sourceDomain, sourceHomeUrl,
                childText(item, "pubDate"), imageUrl(item), null, null, null, query);
    }

    private NewsArticleResponse toAtomArticle(Element entry, NewsSource source, String category, String query) {
        String title = childText(entry, "title");
        String url = atomLink(entry);
        if (title == null || url == null) {
            return null;
        }

        String summary = firstNonBlank(childText(entry, "summary"), childText(entry, "content"));
        String sourceDomain = domainFromUrl(url);
        return article(source, category, title, summary, url, source.name(), sourceDomain, homepageFromDomain(sourceDomain),
                firstNonBlank(childText(entry, "published"), childText(entry, "updated")), imageUrl(entry), null, null, null, query);
    }

    private NewsArticleResponse article(
            NewsSource source,
            String category,
            String title,
            String summary,
            String url,
            String sourceName,
            String sourceDomain,
            String sourceHomeUrl,
            String publishedAt,
            String imageUrl,
            String byline,
            String country,
            String language,
            String query) {
        String cleanedTitle = stripGoogleSource(cleanText(title), sourceName);
        String cleanedSummary = truncate(cleanText(summary), 260);
        String signalType = signalTypeFor(category, cleanedTitle + " " + firstNonBlank(cleanedSummary, ""));
        LocalDateTime parsedDate = parseDate(publishedAt);
        int freshnessScore = freshnessScore(parsedDate);
        List<String> matchedKeywords = matchedKeywords(cleanedTitle, cleanedSummary, query);
        int relevanceScore = relevanceScoreFor(source, signalType, cleanedTitle, cleanedSummary, matchedKeywords, freshnessScore);
        String canonicalUrl = canonicalizeUrl(url);
        String displaySource = firstNonBlank(sourceName, readableDomain(sourceDomain), source.name());

        return NewsArticleResponse.builder()
                .id(Integer.toUnsignedString(Objects.hash(canonicalUrl, cleanedTitle)))
                .provider(source.provider())
                .category(category)
                .signalType(signalType)
                .title(cleanedTitle)
                .summary(cleanedSummary)
                .whyItMatters(whyItMatters(signalType))
                .displayContext(displayContext(signalType, displaySource))
                .sourceName(displaySource)
                .sourceDomain(sourceDomain)
                .sourceType(source.sourceType())
                .sourceTrustTier(source.trustTier())
                .sourceHomeUrl(sourceHomeUrl)
                .sourceUrl(url)
                .canonicalUrl(canonicalUrl)
                .imageUrl(imageUrl)
                .imageAltText(cleanedTitle == null ? null : "News image for " + cleanedTitle)
                .byline(cleanText(byline))
                .country(cleanText(country))
                .language(cleanText(language))
                .publishedAt(parsedDate)
                .relevanceScore(relevanceScore)
                .freshnessScore(freshnessScore)
                .primaryAction("Read")
                .matchedKeywords(matchedKeywords)
                .tags(tags(category, signalType, source.sourceType()))
                .build();
    }

    private NewsPageResponse buildPage(
            List<List<NewsArticleResponse>> groups,
            String category,
            String query,
            List<NewsSource> sources,
            int size) {
        Map<String, NewsArticleResponse> unique = new LinkedHashMap<>();
        groups.stream()
                .flatMap(List::stream)
                .filter(article -> article.getSourceUrl() != null && !article.getSourceUrl().isBlank())
                .forEach(article -> unique.merge(canonicalArticleKey(article), article, this::betterArticle));

        List<NewsArticleResponse> items = unique.values().stream()
                .sorted(Comparator
                        .comparingInt((NewsArticleResponse article) -> article.getRelevanceScore() == null ? 0 : article.getRelevanceScore())
                        .reversed()
                        .thenComparing(NewsArticleResponse::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(size)
                .toList();

        return NewsPageResponse.builder()
                .items(items)
                .page(1)
                .pageSize(size)
                .totalItems(items.size())
                .hasNext(false)
                .provider(ENGINE_PROVIDER)
                .engineVersion(ENGINE_VERSION)
                .category(category)
                .query(query)
                .sourceQueries(sources.stream().map(this::sourceQueryLabel).toList())
                .sourceLabels(sources.stream().map(NewsSource::name).distinct().toList())
                .cached(false)
                .cacheTtlSeconds((int) CACHE_TTL.toSeconds())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private NewsArticleResponse betterArticle(NewsArticleResponse existing, NewsArticleResponse candidate) {
        int existingScore = existing.getRelevanceScore() == null ? 0 : existing.getRelevanceScore();
        int candidateScore = candidate.getRelevanceScore() == null ? 0 : candidate.getRelevanceScore();
        if (candidateScore != existingScore) {
            return candidateScore > existingScore ? candidate : existing;
        }

        LocalDateTime existingDate = existing.getPublishedAt();
        LocalDateTime candidateDate = candidate.getPublishedAt();
        if (existingDate == null) {
            return candidateDate == null ? existing : candidate;
        }
        if (candidateDate == null) {
            return existing;
        }
        return candidateDate.isAfter(existingDate) ? candidate : existing;
    }

    private NewsPageResponse emptyPage(String category, String query, List<NewsSource> sources, int size) {
        return NewsPageResponse.builder()
                .items(List.of())
                .page(1)
                .pageSize(size)
                .totalItems(0)
                .hasNext(false)
                .provider(ENGINE_PROVIDER)
                .engineVersion(ENGINE_VERSION)
                .category(category)
                .query(query)
                .sourceQueries(sources.stream().map(this::sourceQueryLabel).toList())
                .sourceLabels(sources.stream().map(NewsSource::name).distinct().toList())
                .cached(false)
                .cacheTtlSeconds((int) CACHE_TTL.toSeconds())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private NewsPageResponse cachePage(String cacheKey, NewsPageResponse page) {
        Duration ttl = cacheDurationFor(page);
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);
        NewsPageResponse cacheablePage = page.toBuilder()
                .cached(false)
                .cacheTtlSeconds((int) ttl.toSeconds())
                .cacheExpiresAt(expiresAt)
                .build();
        responseCache.put(cacheKey, new CachedNewsPage(cacheablePage, expiresAt));
        return cacheablePage;
    }

    private Duration cacheDurationFor(NewsPageResponse page) {
        return page.getItems() == null || page.getItems().isEmpty() ? EMPTY_CACHE_TTL : CACHE_TTL;
    }

    private NewsPageResponse cachedPage(String cacheKey) {
        CachedNewsPage cached = responseCache.get(cacheKey);
        if (cached == null) {
            return null;
        }

        if (cached.expiresAt().isBefore(LocalDateTime.now())) {
            responseCache.remove(cacheKey);
            return null;
        }

        return cached.response()
                .toBuilder()
                .cached(true)
                .cacheTtlSeconds((int) Duration.between(LocalDateTime.now(), cached.expiresAt()).toSeconds())
                .cacheExpiresAt(cached.expiresAt())
                .build();
    }

    private List<NewsSource> resolveSources(String category, String query, int size) {
        List<NewsSource> sources = new ArrayList<>();
        String gdeltQuery = firstNonBlank(query, GDELT_QUERY_BY_CATEGORY.get(category), GDELT_QUERY_BY_CATEGORY.get("TECH"));
        if (gdeltEnabled && gdeltQuery != null) {
            sources.add(gdelt("GDELT news intelligence", gdeltQuery, Math.max(12, Math.min(size * 2, 30))));
        }

        if (query != null && !query.isBlank()) {
            sources.add(google("Google News search", query));
        }

        sources.addAll(RSS_SOURCES_BY_CATEGORY.getOrDefault(category, RSS_SOURCES_BY_CATEGORY.get("TECH")));
        return sources;
    }

    private NewsSource gdelt(String name, String query, int maxRecords) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String encodedTimespan = URLEncoder.encode(gdeltTimespan, StandardCharsets.UTF_8);
        String url = gdeltBaseUrl + "/api/v2/doc/doc?query=" + encodedQuery
                + "&mode=ArtList&format=json&sort=hybridrel&timespan=" + encodedTimespan
                + "&maxrecords=" + maxRecords;
        return new NewsSource(name, SourceKind.GDELT, "GDELT", url, query, "NEWS_INTELLIGENCE", "MEDIUM");
    }

    private static NewsSource rss(String name, String url) {
        return new NewsSource(name, SourceKind.RSS, "RSS", url, null, "PUBLISHER_RSS", "HIGH");
    }

    private static NewsSource google(String name, String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return new NewsSource(
                name,
                SourceKind.GOOGLE_NEWS_RSS,
                "GOOGLE_NEWS_RSS",
                "https://news.google.com/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en",
                query,
                "SEARCH_AGGREGATOR",
                "MEDIUM"
        );
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "TECH";
        }
        String normalized = category.trim().toUpperCase(Locale.US).replace('-', '_');
        return RSS_SOURCES_BY_CATEGORY.containsKey(normalized) ? normalized : "TECH";
    }

    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        String normalized = query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_QUERY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_QUERY_LENGTH).trim();
    }

    private boolean containsQuery(NewsArticleResponse article, String query) {
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return true;
        }

        String text = String.join(" ",
                article.getTitle() == null ? "" : article.getTitle(),
                article.getSummary() == null ? "" : article.getSummary(),
                article.getSourceName() == null ? "" : article.getSourceName(),
                article.getSourceDomain() == null ? "" : article.getSourceDomain())
                .toLowerCase(Locale.US);

        long matches = terms.stream().filter(text::contains).count();
        return matches >= Math.max(1, Math.ceil(terms.size() * 0.6));
    }

    private DocumentBuilderFactory safeDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory;
    }

    private String childText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return cleanText(nodes.item(0).getTextContent());
    }

    private Element firstChild(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element child)) {
            return null;
        }
        return child;
    }

    private String atomLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int index = 0; index < links.getLength(); index++) {
            if (!(links.item(index) instanceof Element link)) {
                continue;
            }

            String href = cleanText(link.getAttribute("href"));
            String rel = cleanText(link.getAttribute("rel"));
            if (href != null && (rel == null || "alternate".equalsIgnoreCase(rel))) {
                return href;
            }
        }
        return childText(entry, "link");
    }

    private String imageUrl(Element item) {
        String media = imageFromTag(item, "media:content");
        if (media != null) {
            return media;
        }

        media = imageFromTag(item, "media:thumbnail");
        if (media != null) {
            return media;
        }

        NodeList enclosures = item.getElementsByTagName("enclosure");
        if (enclosures.getLength() > 0 && enclosures.item(0) instanceof Element enclosure) {
            String type = cleanText(enclosure.getAttribute("type"));
            String url = cleanText(enclosure.getAttribute("url"));
            if (url != null && (type == null || type.startsWith("image/"))) {
                return url;
            }
        }

        return null;
    }

    private String imageFromTag(Element item, String tagName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        if (nodes.getLength() > 0 && nodes.item(0) instanceof Element media) {
            return cleanText(media.getAttribute("url"));
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isBlank() ? null : text;
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stripGoogleSource(String title, String sourceName) {
        if (title == null || sourceName == null) {
            return title;
        }
        String suffix = " - " + sourceName;
        return title.endsWith(suffix)
                ? title.substring(0, title.length() - suffix.length()).trim()
                : title;
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(value, GDELT_SEEN_DATE_FORMAT);
        } catch (Exception ignored) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime();
            } catch (Exception ignoredAgain) {
                try {
                    return ZonedDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
                } catch (Exception ignoredLast) {
                    try {
                        return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
                    } catch (Exception ignoredFinal) {
                        return null;
                    }
                }
            }
        }
    }

    private String signalTypeFor(String category, String textValue) {
        String text = textValue == null ? "" : textValue.toLowerCase(Locale.US);
        if ("FUNDING".equals(category) || containsAny(text, "funding", "raised", "raises", "series a", "series b", "series c", "seed", "venture", "ipo")) {
            return "FUNDING";
        }
        if (containsAny(text, "layoff", "layoffs", "cuts jobs", "bankruptcy", "shutdown", "breach", "lawsuit")) {
            return "RISK";
        }
        if (containsAny(text, "hiring", "jobs", "recruiting", "expands team", "headcount", "opens office")) {
            return "HIRING";
        }
        if (containsAny(text, "launch", "launches", "unveil", "unveils", "release", "platform", "product", "beta")) {
            return "PRODUCT_LAUNCH";
        }
        if (containsAny(text, "acquires", "acquisition", "merger", "bought")) {
            return "ACQUISITION";
        }
        if (containsAny(text, "partners", "partnership", "teams up")) {
            return "PARTNERSHIP";
        }
        return "COMPANY_SIGNAL";
    }

    private String domainFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.replaceFirst("^www\\.", "");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String canonicalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return url;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("/$", "");
            return (uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.US))
                    + "://"
                    + host.toLowerCase(Locale.US).replaceFirst("^www\\.", "")
                    + path;
        } catch (Exception ignored) {
            return url;
        }
    }

    private String canonicalArticleKey(NewsArticleResponse article) {
        return firstNonBlank(article.getCanonicalUrl(), canonicalizeUrl(article.getSourceUrl()), article.getTitle());
    }

    private String homepageFromDomain(String domain) {
        return domain == null || domain.isBlank() ? null : "https://" + domain.replaceFirst("^www\\.", "");
    }

    private String readableDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
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

    private String whyItMatters(String signalType) {
        return switch (signalType) {
            case "FUNDING" -> "Funding can create hiring waves, new teams, and faster role openings.";
            case "HIRING" -> "Hiring signals can help you time applications before roles get crowded.";
            case "PRODUCT_LAUNCH" -> "Launches often create new product, go-to-market, support, and operations roles.";
            case "ACQUISITION" -> "Acquisitions can change teams, budgets, and interview timing.";
            case "PARTNERSHIP" -> "Partnerships can signal growth areas worth watching before applying.";
            case "RISK" -> "Risk signals help you avoid wasting application time on unstable teams.";
            default -> "Company news gives context before you invest time applying.";
        };
    }

    private String displayContext(String signalType, String sourceName) {
        return labelForSignal(signalType) + " from " + firstNonBlank(sourceName, "AIRRAL news source");
    }

    private String labelForSignal(String signalType) {
        return signalType.toLowerCase(Locale.US).replace('_', ' ');
    }

    private List<String> tags(String category, String signalType, String sourceType) {
        List<String> tags = new ArrayList<>();
        tags.add(category.toLowerCase(Locale.US).replace('_', ' '));
        String signalTag = signalType.toLowerCase(Locale.US).replace('_', ' ');
        if (!tags.contains(signalTag)) {
            tags.add(signalTag);
        }
        if ("NEWS_INTELLIGENCE".equals(sourceType)) {
            tags.add("news intelligence");
        }
        return tags.stream().limit(3).toList();
    }

    private int relevanceScoreFor(
            NewsSource source,
            String signalType,
            String title,
            String summary,
            List<String> matchedKeywords,
            int freshnessScore) {
        int score = 48;
        score += "HIGH".equals(source.trustTier()) ? 14 : 8;
        score += switch (signalType) {
            case "FUNDING", "HIRING" -> 16;
            case "PRODUCT_LAUNCH", "ACQUISITION", "PARTNERSHIP" -> 12;
            case "RISK" -> 10;
            default -> 6;
        };
        score += Math.min(12, matchedKeywords.size() * 4);
        score += Math.max(0, freshnessScore / 6);
        score += summary == null ? 0 : 3;
        score += title == null ? 0 : 2;
        return Math.max(45, Math.min(98, score));
    }

    private int freshnessScore(LocalDateTime publishedAt) {
        if (publishedAt == null) {
            return 35;
        }

        long hours = Math.max(0, Duration.between(publishedAt, LocalDateTime.now()).toHours());
        if (hours <= 6) {
            return 96;
        }
        if (hours <= 24) {
            return 88;
        }
        if (hours <= 72) {
            return 78;
        }
        if (hours <= 168) {
            return 66;
        }
        if (hours <= 720) {
            return 52;
        }
        return 40;
    }

    private List<String> matchedKeywords(String title, String summary, String query) {
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        String text = String.join(" ", firstNonBlank(title, ""), firstNonBlank(summary, "")).toLowerCase(Locale.US);
        return terms.stream()
                .filter(text::contains)
                .distinct()
                .limit(8)
                .toList();
    }

    private List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        return List.of(query.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").split("\\s+")).stream()
                .map(String::trim)
                .filter(term -> term.length() > 2)
                .filter(term -> !List.of("and", "the", "for", "with", "or").contains(term))
                .distinct()
                .toList();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private String sourceQueryLabel(NewsSource source) {
        return source.query() == null ? source.url() : source.provider() + ": " + source.query();
    }

    private String cacheKey(String category, String query, int size) {
        return category + "|" + firstNonBlank(query, "") + "|" + size;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.gdeltproject.org";
        }
        return baseUrl.replaceFirst("/+$", "");
    }

    private ExchangeStrategies newsExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_NEWS_RESPONSE_BYTES))
                .build();
    }

    private enum SourceKind {
        GDELT,
        RSS,
        GOOGLE_NEWS_RSS
    }

    private record NewsSource(
            String name,
            SourceKind kind,
            String provider,
            String url,
            String query,
            String sourceType,
            String trustTier) {}

    private record CachedNewsPage(NewsPageResponse response, LocalDateTime expiresAt) {}
}
