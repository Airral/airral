package com.airral.service;

import com.airral.dto.workday.WorkdayJobDetailResponse;
import com.airral.dto.workday.WorkdayJobSearchRequest;
import com.airral.dto.workday.WorkdayJobSearchResponse;
import com.airral.exception.BadRequestException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Service
public class WorkdayJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    /**
     * Workday answers 429 when its tenants are read faster than it likes. With 42 tenants
     * on wd1/wd5 and six boards synced at once, ~26 were refused within four minutes.
     * Requests are therefore spaced across the whole process, and a refused page is retried
     * with a backoff instead of failing the whole board.
     */
    private final long minIntervalMillis;
    private final AtomicLong nextSlot = new AtomicLong();

    /** A response worth retrying: rate limited or briefly unavailable. */
    static final class RetryableWorkdayException extends RuntimeException {
        final int status;

        RetryableWorkdayException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public WorkdayJobBoardClient(
            WebClient.Builder webClientBuilder,
            @Value("${airral.jobs.workday.insecure-ssl:false}") boolean insecureSsl,
            @Value("${airral.jobs.workday.min-interval-ms:200}") long minIntervalMillis) {
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
        WebClient.Builder builder = webClientBuilder.clone()
                .exchangeStrategies(jobBoardExchangeStrategies())
                .defaultHeader("Accept", "application/json");

        if (insecureSsl) {
            builder.clientConnector(insecureSslConnector());
        }

        this.webClient = builder.build();
    }

    public Mono<WorkdayJobSearchResponse> listJobs(WorkdaySource source, int limit, int offset, String searchText) {
        WorkdayJobSearchRequest request = WorkdayJobSearchRequest.builder()
                // 20, not 100: Workday's CxS endpoint rejects anything larger with
                // 400 Bad Request. The old 100 was never reachable -- the caller
                // already asked for 20 -- so it read as headroom that does not exist.
                .limit(Math.max(1, Math.min(limit, 20)))
                .offset(Math.max(0, offset))
                .searchText(searchText == null ? "" : searchText.trim())
                .build();

        return Mono.defer(() -> Mono.delay(Duration.ofMillis(reserveSlot()))
                        .then(webClient.post()
                                .uri(source.searchUrl())
                                .bodyValue(request)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .map(body -> isRetryable(response.statusCode().value())
                                                ? new RetryableWorkdayException(response.statusCode().value(), failureMessage(source, response.statusCode().value()))
                                                : new BadRequestException(failureMessage(source, response.statusCode().value()))))
                                .bodyToMono(WorkdayJobSearchResponse.class)))
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(3))
                        .maxBackoff(Duration.ofSeconds(45))
                        .filter(error -> error instanceof RetryableWorkdayException)
                        .onRetryExhaustedThrow((spec, signal) -> new BadRequestException(signal.failure().getMessage())));
    }

    /** Milliseconds this request must wait so requests start at least minIntervalMillis apart. */
    long reserveSlot() {
        if (minIntervalMillis == 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long slot = nextSlot.updateAndGet(previous -> Math.max(previous, now) + minIntervalMillis);
        return Math.max(0, slot - minIntervalMillis - now);
    }

    static boolean isRetryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static String failureMessage(WorkdaySource source, int status) {
        return "Unable to load Workday jobs for source: " + source.sourceKey() + " (HTTP " + status + ")";
    }

    public Mono<WorkdayJobDetailResponse> retrieveJob(WorkdaySource source, String externalPath) {
        if (externalPath == null || externalPath.isBlank()) {
            return Mono.error(new BadRequestException("Workday external path is required"));
        }

        return webClient.get()
                .uri(source.detailUrl(externalPath))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Workday job " + externalPath + " for source: " + source.sourceKey() + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(WorkdayJobDetailResponse.class);
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }

    private ReactorClientHttpConnector insecureSslConnector() {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            HttpClient httpClient = HttpClient.create()
                    .resolver(DefaultAddressResolverGroup.INSTANCE)
                    .secure(sslSpec -> sslSpec.sslContext(sslContext));
            return new ReactorClientHttpConnector(httpClient);
        } catch (SSLException e) {
            throw new IllegalStateException("Unable to create local Workday SSL connector", e);
        }
    }

    public record WorkdaySource(String host, String tenant, String site) {
        public static WorkdaySource parse(String boardToken) {
            if (boardToken == null || boardToken.isBlank()) {
                throw new BadRequestException("Workday source must be host|tenant|site");
            }

            String[] parts = boardToken.trim().split("\\|");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new BadRequestException("Workday source must be host|tenant|site");
            }

            String host = parts[0].trim();
            if (host.startsWith("https://")) {
                host = host.substring("https://".length());
            } else if (host.startsWith("http://")) {
                throw new BadRequestException("Workday host must use https");
            }

            return new WorkdaySource(trimTrailingSlash(host), parts[1].trim(), parts[2].trim());
        }

        public String sourceKey() {
            return host + "|" + tenant + "|" + site;
        }

        public String searchUrl() {
            return httpsHost() + "/wday/cxs/" + tenant + "/" + site + "/jobs";
        }

        public String detailUrl(String externalPath) {
            String path = externalPath.startsWith("/") ? externalPath : "/" + externalPath;
            return httpsHost() + "/wday/cxs/" + tenant + "/" + site + path;
        }

        public String publicUrl(String externalPath) {
            String path = externalPath.startsWith("/") ? externalPath : "/" + externalPath;
            return httpsHost() + "/" + site + path;
        }

        private String httpsHost() {
            return "https://" + host;
        }

        private static String trimTrailingSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }
}
