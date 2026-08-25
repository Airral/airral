package com.airral.service;

import com.airral.dto.smartrecruiters.SmartRecruitersPostingResponse;
import com.airral.exception.BadRequestException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
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
public class SmartRecruitersJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public SmartRecruitersJobBoardClient(
            WebClient.Builder webClientBuilder,
            @Value("${airral.jobs.smartrecruiters.insecure-ssl:false}") boolean insecureSsl) {
        WebClient.Builder builder = webClientBuilder.clone()
                .baseUrl("https://api.smartrecruiters.com")
                .exchangeStrategies(jobBoardExchangeStrategies());

        if (insecureSsl) {
            builder.clientConnector(insecureSslConnector());
        }

        this.webClient = builder.build();
    }

    public Mono<SmartRecruitersPostingResponse> listJobs(String companyIdentifier, int limit, int offset, String country) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/v1/companies/{companyIdentifier}/postings")
                            .queryParam("limit", Math.max(1, Math.min(limit, 100)))
                            .queryParam("offset", Math.max(0, offset));
                    if (country != null && !country.isBlank()) {
                        builder.queryParam("country", country.trim().toLowerCase());
                    }
                    return builder.build(normalizeCompanyIdentifier(companyIdentifier));
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load SmartRecruiters jobs for company: " + companyIdentifier + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(SmartRecruitersPostingResponse.class);
    }

    public Mono<SmartRecruitersPostingResponse.Posting> retrieveJob(String companyIdentifier, String postingId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/companies/{companyIdentifier}/postings/{postingId}")
                        .build(normalizeCompanyIdentifier(companyIdentifier), normalizePostingId(postingId)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load SmartRecruiters job " + postingId + " for company: " + companyIdentifier + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(SmartRecruitersPostingResponse.Posting.class);
    }

    private String normalizeCompanyIdentifier(String companyIdentifier) {
        if (companyIdentifier == null || companyIdentifier.isBlank()) {
            throw new BadRequestException("SmartRecruiters company identifier is required");
        }

        return companyIdentifier.trim();
    }

    private String normalizePostingId(String postingId) {
        if (postingId == null || postingId.isBlank()) {
            throw new BadRequestException("SmartRecruiters posting id is required");
        }

        return postingId.trim();
    }

    private ReactorClientHttpConnector insecureSslConnector() {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            HttpClient httpClient = HttpClient.create()
                    .secure(sslSpec -> sslSpec.sslContext(sslContext));
            return new ReactorClientHttpConnector(httpClient);
        } catch (SSLException e) {
            throw new IllegalStateException("Unable to create local SmartRecruiters SSL connector", e);
        }
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
