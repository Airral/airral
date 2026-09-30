package com.airral.controller;

import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import com.airral.security.TurnstileVerifier;
import com.airral.service.AccountVerificationService;
import com.airral.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * With Turnstile set up, an employer sign-up has to carry a person's token
 * before any account or company is made. A job seeker's sign-up is not asked.
 */
class EmployerSignUpBotCheckTest {

    private final AuthService authService = mock(AuthService.class);
    private AuthController controller;

    @BeforeEach
    void setUp() {
        LoginThrottle throttle = mock(LoginThrottle.class);
        when(throttle.checkAddress(anyString())).thenReturn(Mono.empty());
        when(throttle.recordAddressAttempt(anyString())).thenReturn(Mono.empty());
        AccountVerificationService verification = mock(AccountVerificationService.class);
        when(verification.sendVerificationAfterSignup(any())).thenReturn(Mono.empty());
        when(authService.register(any())).thenReturn(Mono.just(AuthResponse.builder().userId(7L).emailVerified(false).build()));

        // Cloudflare refuses whatever it is sent: only a request that never asks gets through.
        TurnstileVerifier refusing = new TurnstileVerifier(WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"success\":false}")
                        .build())), "test-secret");
        controller = new AuthController(authService, throttle, mock(TokenVersionCache.class), mock(JwtTokenProvider.class),
                verification, mock(com.airral.repository.UserRepository.class),
                mock(com.airral.repository.OrganizationRepository.class), mock(com.airral.service.UserService.class),
                refusing);
    }

    private static RegisterRequest signUp(String companyName) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("amy@acme.io");
        request.setPassword("Secret123");
        request.setFirstName("Amy");
        request.setLastName("Adams");
        request.setCompanyName(companyName);
        return request;
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/register").build());
    }

    @Test
    @DisplayName("an employer sign-up that fails the bot check makes no account and no company")
    void botIsRefusedBeforeAnythingExists() {
        StepVerifier.create(controller.register(signUp("Acme"), exchange()))
                .expectError(BadRequestException.class)
                .verify();
        verify(authService, never()).register(any());
    }

    @Test
    @DisplayName("a job seeker's sign-up is not asked for the check")
    void applicantSignUpIsNotChecked() {
        StepVerifier.create(controller.register(signUp(null), exchange()))
                .expectNextCount(1)
                .verifyComplete();
        verify(authService).register(any());
    }
}
