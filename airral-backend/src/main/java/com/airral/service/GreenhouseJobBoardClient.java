package com.airral.service;

import com.airral.dto.greenhouse.GreenhouseJobBoardResponse;
import com.airral.exception.BadRequestException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class GreenhouseJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public GreenhouseJobBoardClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.clone()
                .baseUrl("https://boards-api.greenhouse.io")
                .exchangeStrategies(jobBoardExchangeStrategies())
                .build();
    }

    public Mono<GreenhouseJobBoardResponse> listJobs(String boardToken) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/boards/{boardToken}/jobs")
                        .queryParam("content", false)
                        .build(normalizeBoardToken(boardToken)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Greenhouse jobs for board: " + boardToken)))
                .bodyToMono(GreenhouseJobBoardResponse.class);
    }

    public Mono<GreenhouseJobBoardResponse.GreenhouseJob> retrieveJob(String boardToken, Long jobId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/boards/{boardToken}/jobs/{jobId}")
                        .queryParam("pay_transparency", true)
                        .build(normalizeBoardToken(boardToken), jobId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Greenhouse job " + jobId + " for board: " + boardToken)))
                .bodyToMono(GreenhouseJobBoardResponse.GreenhouseJob.class);
    }

    private String normalizeBoardToken(String boardToken) {
        if (boardToken == null || boardToken.isBlank()) {
            throw new BadRequestException("Greenhouse board token is required");
        }

        return boardToken.trim().toLowerCase();
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
