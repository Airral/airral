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

    /**
     * Raised from 16MB when the list call started asking for {@code content}.
     * A large board is a few thousand postings and the body is the bulk of each
     * one, which overruns the old ceiling and fails the whole board with a
     * buffer-limit error rather than a partial result.
     *
     * <p>Affordable because {@link #listJobs} has exactly one caller, in the
     * sync path, and the sync runs on a GitHub Actions runner with 7GB rather
     * than on a Cloud Run instance. Nothing on a request path allocates this.
     */
    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 64 * 1024 * 1024;

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
                        // Both of these were previously withheld from the list
                        // call, which is the only call the sync makes. Without
                        // content there is no text to derive anything from, and
                        // without pay_transparency every posting was stamped
                        // "Salary not listed" regardless of what the employer
                        // published. The detail endpoint below always asked for
                        // pay; the sync never did.
                        .queryParam("content", true)
                        .queryParam("pay_transparency", true)
                        .build(normalizeBoardToken(boardToken)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Greenhouse jobs for board: " + boardToken + " (HTTP " + response.statusCode().value() + ")")))
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
