package com.airral.service;

import com.airral.dto.ashby.AshbyJobBoardResponse;
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
public class AshbyJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public AshbyJobBoardClient(
            WebClient.Builder webClientBuilder,
            @Value("${airral.jobs.ashby.insecure-ssl:false}") boolean insecureSsl) {
        WebClient.Builder builder = webClientBuilder.clone()
                .baseUrl("https://api.ashbyhq.com")
                .exchangeStrategies(jobBoardExchangeStrategies());

        if (insecureSsl) {
            builder.clientConnector(insecureSslConnector());
        }

        this.webClient = builder.build();
    }

    public Mono<AshbyJobBoardResponse> listJobs(String boardName) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/posting-api/job-board/{boardName}")
                        .queryParam("includeCompensation", true)
                        .build(normalizeBoardName(boardName)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Ashby jobs for board: " + boardName)))
                .bodyToMono(AshbyJobBoardResponse.class);
    }

    private String normalizeBoardName(String boardName) {
        if (boardName == null || boardName.isBlank()) {
            throw new BadRequestException("Ashby board name is required");
        }

        return boardName.trim();
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
            throw new IllegalStateException("Unable to create local Ashby SSL connector", e);
        }
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
