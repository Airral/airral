package com.airral.service;

import com.airral.dto.lever.LeverPostingResponse;
import com.airral.exception.BadRequestException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class LeverJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public LeverJobBoardClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.clone()
                .baseUrl("https://api.lever.co")
                .exchangeStrategies(jobBoardExchangeStrategies())
                .build();
    }

    public Mono<List<LeverPostingResponse>> listJobs(String siteName, int limit) {
        return listJobs(siteName, limit, 0);
    }

    /**
     * One page of a Lever board.
     *
     * <p>100 is Lever's own per-request maximum, so a board larger than that can
     * only be read by walking {@code skip}. Verified against the lifestance board
     * on 2026-09-21: skip=0 and skip=100 each returned 100 postings with no id in
     * common, and the final page came back short. Before this took a skip, every
     * Lever board was truncated at its first 100 postings.
     */
    public Mono<List<LeverPostingResponse>> listJobs(String siteName, int limit, int skip) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v0/postings/{siteName}")
                        .queryParam("mode", "json")
                        .queryParam("limit", Math.max(1, Math.min(limit, 100)))
                        .queryParam("skip", Math.max(0, skip))
                        .build(normalizeSiteName(siteName)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Lever jobs for site: " + siteName + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(new ParameterizedTypeReference<>() {});
    }

    public Mono<LeverPostingResponse> retrieveJob(String siteName, String postingId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v0/postings/{siteName}/{postingId}")
                        .queryParam("mode", "json")
                        .build(normalizeSiteName(siteName), normalizePostingId(postingId)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load Lever job " + postingId + " for site: " + siteName)))
                .bodyToMono(LeverPostingResponse.class);
    }

    private String normalizeSiteName(String siteName) {
        if (siteName == null || siteName.isBlank()) {
            throw new BadRequestException("Lever site name is required");
        }

        return siteName.trim();
    }

    private String normalizePostingId(String postingId) {
        if (postingId == null || postingId.isBlank()) {
            throw new BadRequestException("Lever posting id is required");
        }

        return postingId.trim();
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
