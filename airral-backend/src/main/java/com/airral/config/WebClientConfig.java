package com.airral.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    /**
     * Shared builder for every outbound call, with compression on.
     *
     * <p>Reactor Netty defaults {@code compress} to false, so we were sending no
     * Accept-Encoding and every job board was answering uncompressed. That was
     * survivable while the Greenhouse list call asked for metadata only; it stopped
     * being survivable when it started asking for posting bodies, which grew the
     * largest measured board from a few hundred kilobytes to 39MB.
     *
     * <p>Measured on that board: 39,250,289 bytes in 11.8s uncompressed against
     * 3,648,257 bytes in 1.1s with gzip -- about 10x smaller and 10x faster. Job
     * board responses are JSON and HTML, which is close to the best case for gzip,
     * and the CPU cost of inflating them is trivial next to the transfer they
     * replace.
     *
     * <p>This matters for more than speed. The per-call deadline is a fixed number
     * of seconds, so a payload that grows without the deadline moving turns into a
     * timeout -- and a timed-out board is not a 404, so it is not auto-disabled,
     * the run still reports partial success, and the board's postings quietly age
     * out of the catalogue instead.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().compress(true)));
    }
}
