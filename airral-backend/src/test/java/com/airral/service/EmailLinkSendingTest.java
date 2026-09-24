package com.airral.service;

import com.airral.domain.User;
import com.airral.exception.EmailLinkRateLimitedException;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who AIRRAL asks Firebase to email, and when.
 *
 * <p>The links used to be requested by the browser, which mailed any address
 * anyone typed. These pin the rules that replaced that: a reset link only for an
 * active account, a verification link only for the signed-in account's own
 * address, a per-account cap on both, and forgot-password answering the same way
 * -- after the same delay -- whether or not anything was sent.
 */
class EmailLinkSendingTest {

    private final UserRepository users = mock(UserRepository.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private final FirebaseEmailLinkSender sender = mock(FirebaseEmailLinkSender.class);
    private AccountVerificationService service;

    @BeforeEach
    void setUp() {
        service = new AccountVerificationService(mock(FirebaseIdentityService.class), users,
                mock(PasswordEncoder.class), mock(TokenVersionCache.class), throttle,
                mock(CompanyVerificationService.class), sender);
        when(throttle.emailLinkAllowed(anyLong())).thenReturn(Mono.just(true));
        when(throttle.recordEmailLink(anyLong())).thenReturn(Mono.empty());
        when(sender.send(any(), any())).thenReturn(Mono.empty());
        when(users.findByEmail(any())).thenReturn(Mono.empty());
    }

    private User account(boolean active, boolean verified) {
        return User.builder().id(7L).email("amy@example.com").isActive(active).emailVerified(verified).build();
    }

    @Test
    @DisplayName("forgot-password emails an active account")
    void resetGoesToARealAccount() {
        when(users.findByEmail("amy@example.com")).thenReturn(Mono.just(account(true, false)));

        StepVerifier.withVirtualTime(() -> service.requestPasswordReset("  Amy@Example.com "))
                .thenAwait(AccountVerificationService.FORGOT_PASSWORD_MIN_RESPONSE)
                .verifyComplete();

        verify(sender).send(any(), org.mockito.ArgumentMatchers.eq(FirebaseEmailLinkSender.Purpose.RESET));
    }

    @Test
    @DisplayName("forgot-password emails nobody for an address with no account")
    void resetForAStrangerSendsNothing() {
        StepVerifier.withVirtualTime(() -> service.requestPasswordReset("stranger@example.com"))
                .thenAwait(AccountVerificationService.FORGOT_PASSWORD_MIN_RESPONSE)
                .verifyComplete();

        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("forgot-password takes the same minimum time whether or not it sends")
    void resetTimingDoesNotLeak() {
        // No account: the lookup is instant, and the answer still waits the full floor.
        StepVerifier.withVirtualTime(() -> service.requestPasswordReset("stranger@example.com"))
                .expectSubscription()
                .expectNoEvent(AccountVerificationService.FORGOT_PASSWORD_MIN_RESPONSE.minusMillis(1))
                .thenAwait(java.time.Duration.ofMillis(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("forgot-password emails nobody for a deactivated account")
    void resetForADeactivatedAccountSendsNothing() {
        when(users.findByEmail("amy@example.com")).thenReturn(Mono.just(account(false, true)));
        StepVerifier.withVirtualTime(() -> service.requestPasswordReset("amy@example.com"))
                .thenAwait(AccountVerificationService.FORGOT_PASSWORD_MIN_RESPONSE)
                .verifyComplete();
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("forgot-password over the per-account limit sends nothing and still answers normally")
    void resetOverLimitIsSilent() {
        when(users.findByEmail("amy@example.com")).thenReturn(Mono.just(account(true, true)));
        when(throttle.emailLinkAllowed(7L)).thenReturn(Mono.just(false));
        StepVerifier.withVirtualTime(() -> service.requestPasswordReset("amy@example.com"))
                .thenAwait(AccountVerificationService.FORGOT_PASSWORD_MIN_RESPONSE)
                .verifyComplete();
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("a failure to send never reaches the forgot-password caller")
    void resetSendFailureIsSwallowed() {
        // An error only real accounts can produce would reveal which ones exist.
        when(users.findByEmail("amy@example.com")).thenReturn(Mono.just(account(true, true)));
        when(sender.send(any(), any())).thenReturn(Mono.error(new IllegalStateException("Firebase down")));
        StepVerifier.withVirtualTime(() -> service.requestPasswordReset("amy@example.com"))
                .thenAwait(AccountVerificationService.FORGOT_PASSWORD_MIN_RESPONSE)
                .verifyComplete();
    }

    @Test
    @DisplayName("resend goes to the signed-in account's own address")
    void verificationGoesToTheOwner() {
        when(users.findById(7L)).thenReturn(Mono.just(account(true, false)));
        StepVerifier.create(service.sendVerification(7L))
                .expectNext(AccountVerificationService.VerificationSend.SENT)
                .verifyComplete();
        verify(sender).send(any(), org.mockito.ArgumentMatchers.eq(FirebaseEmailLinkSender.Purpose.VERIFY));
    }

    @Test
    @DisplayName("an already verified account is not sent another link")
    void verifiedAccountsGetNoMail() {
        when(users.findById(7L)).thenReturn(Mono.just(account(true, true)));
        StepVerifier.create(service.sendVerification(7L))
                .expectNext(AccountVerificationService.VerificationSend.ALREADY_VERIFIED)
                .verifyComplete();
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("resend over the limit says so, since the owner is asking about their own account")
    void verificationOverLimitIs429() {
        when(users.findById(7L)).thenReturn(Mono.just(account(true, false)));
        when(throttle.emailLinkAllowed(7L)).thenReturn(Mono.just(false));
        StepVerifier.create(service.sendVerification(7L))
                .expectError(EmailLinkRateLimitedException.class)
                .verify();
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("a failed send after sign-up never fails the sign-up")
    void signupSendFailureIsSwallowed() {
        when(users.findById(7L)).thenReturn(Mono.just(account(true, false)));
        when(sender.send(any(), any())).thenReturn(Mono.error(new IllegalStateException("Firebase down")));
        StepVerifier.create(service.sendVerificationAfterSignup(7L)).verifyComplete();
    }
}
