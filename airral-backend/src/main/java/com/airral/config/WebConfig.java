package com.airral.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Global Web Configuration
 * Handles CORS settings for all endpoints
 *
 * Exposed as a CorsConfigurationSource bean rather than through
 * WebFluxConfigurer.addCorsMappings, because SecurityConfig calls
 * ServerHttpSecurity.cors() and that looks for exactly this bean. Without one,
 * its CORS support silently did nothing and the only CORS filter lived outside
 * the security chain -- so an unauthenticated request was rejected by the
 * authentication entry point before any CORS header was written.
 *
 * The browser could then not read the 401 and reported it as "blocked by CORS
 * policy" instead, which points the reader at an allowlist that was correct all
 * along. Verified before the change: /api/jobs/open returned 200 with an
 * Access-Control-Allow-Origin header, /api/jobs returned 401 with none.
 *
 * Deliberately the only CORS registration. Two sources would each append
 * Access-Control-Allow-Origin, and a duplicated header is rejected by browsers.
 */
@Configuration
public class WebConfig {

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
