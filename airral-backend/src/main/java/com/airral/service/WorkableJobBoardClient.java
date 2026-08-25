package com.airral.service;

import com.airral.dto.workable.WorkableJobBoardResponse;
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
public class WorkableJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public WorkableJobBoardClient(
            WebClient.Builder webClientBuilder,
            @Value("${airral.jobs.workable.insecure-ssl:false}") boolean insecureSsl) {
        WebClient.Builder builder = webClientBuilder.clone()
                .baseUrl("https://apply.workable.com/api/v1/widget")
                .exchangeStrategies(jobBoardExchangeStrategies());

        if (insecureSsl) {
            builder.clientConnector(insecureSslConnector());
        }

        this.webClient = builder.build();
    }

    public Mono<WorkableJobBoardResponse> listJobs(String subdomain, boolean includeDetails) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/accounts/{subdomain}")
                        .queryParam("details", includeDetails)
                        .build(normalizeSubdomain(subdomain)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Workable jobs for account: " + subdomain + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(WorkableJobBoardResponse.class);
    }

    private String normalizeSubdomain(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            throw new BadRequestException("Workable account subdomain is required");
        }
        return subdomain.trim();
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
            throw new IllegalStateException("Unable to create local Workable SSL connector", e);
        }
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
