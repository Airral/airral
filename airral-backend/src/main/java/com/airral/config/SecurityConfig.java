package com.airral.config;

import com.airral.security.AuthenticationManager;
import com.airral.security.SecurityContextRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import com.airral.exception.ApiKeyRejectedException;
import com.airral.security.ApiKeyStore;
import com.airral.security.SecurityContextRepository;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public SecurityConfig(AuthenticationManager authenticationManager,
                         SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Strength 12 for security
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .cors(cors -> { })
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        // Writes a body only when there is something useful to
                        // say. SecurityContextRepository leaves the reason an
                        // API key was refused on the exchange, because that is
                        // where it is known and here is where a 401 is
                        // rendered. Everything else stays a bare 401: a session
                        // token failing needs no explanation, and inventing one
                        // would describe the auth scheme to whoever is probing.
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);

                            Object reason = exchange.getAttributes()
                                    .get(SecurityContextRepository.KEY_REJECTION_ATTRIBUTE);
                            if (!(reason instanceof ApiKeyStore.MissReason missReason)) {
                                return Mono.empty();
                            }

                            String message = ApiKeyRejectedException.describe(missReason);
                            byte[] body = ("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\""
                                    + message + "\"}").getBytes(StandardCharsets.UTF_8);
                            exchange.getResponse().getHeaders()
                                    .setContentType(MediaType.APPLICATION_JSON);
                            return exchange.getResponse().writeWith(Mono.just(
                                    exchange.getResponse().bufferFactory().wrap(body)));
                        })
                        .accessDeniedHandler((exchange, denied) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return Mono.empty();
                        })
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .authenticationManager(authenticationManager)
                .securityContextRepository(securityContextRepository)
                .authorizeExchange(exchanges -> exchanges
                    .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public endpoints
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/google").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/applications").permitAll()
                        
                        // Swagger/OpenAPI
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                        .pathMatchers("/api-docs/**").permitAll()
                        
                        // Actuator (health check)
                        .pathMatchers("/actuator/health").permitAll()

                        // Public candidate job discovery reads normalized public ATS data
                        .pathMatchers(HttpMethod.GET, "/api/candidate/jobs/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/jobs/open").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/jobs/statistics/public").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/jobs/*").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/feed").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/feed/signals").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/feed/news").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/seo/**").permitAll()
                        
                        // Key management is admin-only, and reached with a
                        // session token rather than an API key: issuing
                        // credentials from a credential would let a leaked
                        // admin key mint its own replacements, so revoking it
                        // would no longer be final.
                        .pathMatchers("/api/admin/**").hasAuthority("ADMIN")

                        // MCP falls through to authenticated() below. The
                        // endpoint itself grants nothing on role alone: tools
                        // are filtered by the scopes on the presented key, so a
                        // session token reaching it sees an empty tool list.

                        // All other endpoints require authentication
                        .anyExchange().authenticated()
                )
                .build();
    }
}
