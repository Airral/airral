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
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Service
public class WorkdayJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public WorkdayJobBoardClient(
            WebClient.Builder webClientBuilder,
            @Value("${airral.jobs.workday.insecure-ssl:false}") boolean insecureSsl) {
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
                .limit(Math.max(1, Math.min(limit, 100)))
                .offset(Math.max(0, offset))
                .searchText(searchText == null ? "" : searchText.trim())
                .build();

        return webClient.post()
                .uri(source.searchUrl())
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Workday jobs for source: " + source.sourceKey() + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(WorkdayJobSearchResponse.class);
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
