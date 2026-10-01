package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;

class SecurityContextRepositoryTest {

    @Test
    @DisplayName("a key is authenticated once per request, however often the context is read")
    void keyIsResolvedOncePerRequest() {
        ApiKeyAuthenticationManager keys = mock(ApiKeyAuthenticationManager.class);
        when(keys.authenticate(any())).thenReturn(Mono.just(new UsernamePasswordAuthenticationToken(
                "owner@example.com", null, List.of(new SimpleGrantedAuthority(ApiKeyScopes.CREDENTIAL_AUTHORITY)))));
        SecurityContextRepository repository = new SecurityContextRepository(
                mock(AuthenticationManager.class), keys, mock(ApiKeyStore.class), new AiAccessPolicy(""));

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer airral_ak_live_abcd1234_" + "x".repeat(43)));

        // Filter, authorisation and the controller each read the context.
        for (int read = 0; read < 3; read++) {
            assertEquals("owner@example.com",
                    repository.load(exchange).block().getAuthentication().getName());
        }
        verify(keys, times(1)).authenticate(any());
    }
}
