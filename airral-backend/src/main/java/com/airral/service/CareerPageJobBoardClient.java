package com.airral.service;

import com.airral.exception.BadRequestException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class CareerPageJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public CareerPageJobBoardClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.clone()
                .exchangeStrategies(jobBoardExchangeStrategies())
                .build();
    }

    public Mono<String> fetchPage(String url, String sourceName) {
        String normalizedUrl = normalizeUrl(url, sourceName);
        return webClient.get()
                .uri(normalizedUrl)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load " + sourceName + " career page: " + normalizedUrl + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(String.class);
    }

    private String normalizeUrl(String url, String sourceName) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException(sourceName + " career page URL is required");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("https://")) {
            throw new BadRequestException(sourceName + " career page URL must be an https URL");
        }
        return trimmed;
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
