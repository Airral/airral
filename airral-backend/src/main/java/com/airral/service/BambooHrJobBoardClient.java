package com.airral.service;

import com.airral.dto.bamboohr.BambooHrJobSummaryResponse;
import com.airral.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Service
public class BambooHrJobBoardClient {

    private static final int MAX_JOB_BOARD_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;
    private final String apiKey;

    public BambooHrJobBoardClient(
            WebClient.Builder webClientBuilder,
            @Value("${airral.jobs.bamboohr.api-key:}") String apiKey) {
        this.webClient = webClientBuilder.clone()
                .exchangeStrategies(jobBoardExchangeStrategies())
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public Mono<List<BambooHrJobSummaryResponse>> listJobs(String companyDomain) {
        if (apiKey.isBlank()) {
            return Mono.error(new BadRequestException("BambooHR source requires airral.jobs.bamboohr.api-key"));
        }

        return webClient.get()
                .uri("https://" + normalizeCompanyDomain(companyDomain) + ".bamboohr.com/api/v1/applicant_tracking/jobs?statusGroups=Open&sortBy=created&sortOrder=DESC")
                .header(HttpHeaders.AUTHORIZATION, basicAuth(apiKey, "x"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BadRequestException("Unable to load BambooHR jobs for company: " + companyDomain + " (HTTP " + response.statusCode().value() + ")")))
                .bodyToMono(new ParameterizedTypeReference<>() {});
    }

    private String normalizeCompanyDomain(String companyDomain) {
        if (companyDomain == null || companyDomain.isBlank()) {
            throw new BadRequestException("BambooHR company domain is required");
        }
        return companyDomain.trim().replace(".bamboohr.com", "");
    }

    private String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ExchangeStrategies jobBoardExchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_JOB_BOARD_RESPONSE_BYTES))
                .build();
    }
}
