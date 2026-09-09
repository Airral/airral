package com.airral.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;

import com.airral.dto.request.GoogleAuthRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.security.JwtTokenProvider;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import com.airral.service.AuthService;

import reactor.core.publisher.Mono;

/**
 * The defect this pins was never in the sign-in logic, which worked: it was that
 * nothing was mapped to the path, so POST /api/auth/google answered 404 "No
 * static resource api/auth/google." while the "Continue with Google" button
 * rendered on both portals. A test that only exercised the method body would
 * have passed throughout, so the mapping itself is asserted here.
 */
class AuthGoogleRouteTest {

    private AuthService authService;
    private LoginThrottle loginThrottle;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        loginThrottle = mock(LoginThrottle.class);

        when(loginThrottle.checkAddress(anyString())).thenReturn(Mono.empty());
        when(loginThrottle.recordAddressAttempt(anyString())).thenReturn(Mono.empty());

        controller = new AuthController(
                authService,
                loginThrottle,
                mock(TokenVersionCache.class),
                mock(JwtTokenProvider.class));
    }

    private ServerWebExchange exchangeFrom(String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/google")
                .header("X-Forwarded-For", forwardedFor)
                .build());
    }

    @Test
    @DisplayName("POST /api/auth/google is mapped")
    void googleRouteIsMapped() throws Exception {
        // SecurityConfig allow-lists exactly POST /api/auth/google and the
        // frontend posts to exactly /auth/google against the /api base, so both
        // halves of the path are worth holding still, not just the method body.
        Method handler = AuthController.class.getMethod(
                "loginWithGoogle", GoogleAuthRequest.class, ServerWebExchange.class);

        PostMapping mapping = handler.getAnnotation(PostMapping.class);
        assertNotNull(mapping, "the handler must be mapped, or the route 404s as it did in production");
        assertArrayEquals(new String[] {"/google"}, mapping.value());

        RequestMapping base = AuthController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/auth"}, base.value());
    }

    @Test
    @DisplayName("a verified credential answers 200 with the service's response")
    void verifiedCredentialAnswersOk() {
        AuthResponse response = AuthResponse.builder()
                .token("jwt")
                .email("applicant@example.com")
                .message("Google sign-in successful")
                .build();
        when(authService.loginWithGoogle(any())).thenReturn(Mono.just(response));

        ResponseEntity<AuthResponse> result = controller
                .loginWithGoogle(new GoogleAuthRequest("header.payload.signature"), exchangeFrom("203.0.113.4"))
                .block();

        assertNotNull(result);
        // 200 rather than /register's 201: the same route serves an existing
        // account signing in and a new one being created.
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());

        ArgumentCaptor<GoogleAuthRequest> sent = ArgumentCaptor.forClass(GoogleAuthRequest.class);
        verify(authService).loginWithGoogle(sent.capture());
        assertEquals("header.payload.signature", sent.getValue().getCredential(),
                "the credential must reach the service unchanged, or verification fails against Google");
    }

    @Test
    @DisplayName("throttled on the address alone, never on an email")
    void throttlesOnTheAddressOnly() {
        when(authService.loginWithGoogle(any())).thenReturn(Mono.just(AuthResponse.builder().build()));

        controller.loginWithGoogle(new GoogleAuthRequest("header.payload.signature"), exchangeFrom("203.0.113.4"))
                .block();

        // This route creates an applicant account when the Google address is new,
        // so it is an account-creation path and is counted like /register. The
        // email bucket is not available to it anyway -- the request carries only a
        // signed credential, and the address inside it is unknown until Google's
        // signature has been checked.
        verify(loginThrottle).checkAddress("203.0.113.4");
        verify(loginThrottle).recordAddressAttempt("203.0.113.4");
        verify(loginThrottle, never()).check(any(), any());
        verify(loginThrottle, never()).recordFailure(any(), any());
    }
}
