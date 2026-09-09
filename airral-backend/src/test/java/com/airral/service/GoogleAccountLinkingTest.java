package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.GoogleAuthRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.exception.UnauthorizedException;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which account a verified Google credential is allowed to open.
 *
 * <p>The credential check was never the weak part. The linking rule was: it
 * found a row by the address in the credential and signed into it. Registration
 * is public, creates applicants with emailVerified false, and never sends a
 * verification mail -- SMTP does not work from Cloud Run here -- so an address
 * in the users table is only evidence that somebody typed it. Register
 * victim@example.com first, wait for the real owner to press "Continue with
 * Google", and the platform hands them your row while your password still opens
 * it. Sub-first matching plus a refusal to adopt an unproven row is what closes
 * that, so both halves are pinned here alongside the two paths that must keep
 * working.
 *
 * <p>Pinned before it could ever fire: POST /api/auth/google had no handler
 * until the mapping added in the same change, so the address-only match never
 * ran in production.
 */
class GoogleAccountLinkingTest {

    private static final String CREDENTIAL = "header.payload.signature";
    private static final String VICTIM_EMAIL = "victim@example.com";
    private static final String GOOGLE_SUB = "104729166431982374615";
    private static final String OTHER_GOOGLE_SUB = "118203847561092837465";

    private UserRepository userRepository;
    private CandidateProfileRepository candidateProfileRepository;
    private GoogleIdentityService googleIdentityService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        candidateProfileRepository = mock(CandidateProfileRepository.class);
        googleIdentityService = mock(GoogleIdentityService.class);

        authService = new AuthService(
                userRepository,
                mock(OrganizationRepository.class),
                candidateProfileRepository,
                mock(PasswordEncoder.class),
                mock(JwtTokenProvider.class),
                new ObjectMapper(),
                googleIdentityService);

        // Google has already checked the signature, the audience, the expiry and
        // email_verified by the time AuthService sees this. Everything under test
        // is what happens next.
        when(googleIdentityService.verifyCredential(anyString()))
                .thenReturn(Mono.just(new GoogleIdentityService.GoogleProfile(
                        GOOGLE_SUB, VICTIM_EMAIL, "Ada", "Lovelace", "Ada Lovelace", null)));

        when(candidateProfileRepository.existsByUserId(any())).thenReturn(Mono.just(true));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> Mono.just(invocation.<User>getArgument(0)));
    }

    @Test
    @DisplayName("an unverified row at the address is never signed into")
    void refusesThePreRegisteredRow() {
        // Exactly the attacker's row: created through the public /register with a
        // password they chose, and unverified because nothing here ever verifies.
        User preRegistered = applicantRow(11L, VICTIM_EMAIL, null, false);
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.empty());
        when(userRepository.findByEmail(VICTIM_EMAIL)).thenReturn(Mono.just(preRegistered));

        StepVerifier.create(authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)))
                .verifyError(UnauthorizedException.class);

        // No token, and just as importantly no write: the old code flipped
        // emailVerified to true on the way past, which laundered the attacker's
        // row into one that would pass this very check next time.
        verify(userRepository, never()).save(any(User.class));
        assertThat(preRegistered.getGoogleSubject()).isNull();
        assertThat(preRegistered.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("the refusal says no more than /register's duplicate response already does")
    void refusalDoesNotEnumerate() {
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.empty());
        when(userRepository.findByEmail(VICTIM_EMAIL))
                .thenReturn(Mono.just(applicantRow(11L, VICTIM_EMAIL, null, false)));

        StepVerifier.create(authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)))
                .consumeErrorWith(error -> {
                    assertThat(error).isInstanceOf(UnauthorizedException.class);
                    // Has to be actionable, because the person reading it is the
                    // genuine owner of the address and their way in is the
                    // password form.
                    assertThat(error.getMessage()).containsIgnoringCase("password");
                    // "An account exists here" is what POST /api/auth/register
                    // already answers with 409. Anything past that -- whether the
                    // row was verified, what role it holds, whether another Google
                    // identity sits on it -- would tell someone which of the
                    // addresses they pre-registered a real person has come for.
                    assertThat(error.getMessage())
                            .doesNotContainIgnoringCase("verif")
                            .doesNotContainIgnoringCase("google")
                            .doesNotContainIgnoringCase("admin");
                })
                .verify();
    }

    @Test
    @DisplayName("the row holding the sub is the one signed into, address notwithstanding")
    void matchesOnTheSubBeforeTheAddress() {
        User linked = applicantRow(21L, VICTIM_EMAIL, GOOGLE_SUB, true);
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.just(linked));
        // Stubbed so that a fall-through to the address would be visible as a
        // sign-in to the wrong row rather than as a null pointer.
        when(userRepository.findByEmail(VICTIM_EMAIL))
                .thenReturn(Mono.just(applicantRow(22L, VICTIM_EMAIL, null, false)));

        StepVerifier.create(authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)))
                .assertNext(response -> {
                    assertThat(response.getUserId()).isEqualTo(21L);
                    assertThat(response.getMessage()).isEqualTo("Google sign-in successful");
                })
                .verifyComplete();

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("a sub match does not rewrite the row's address from the credential")
    void keepsTheStoredAddressOnASubMatch() {
        // The Google account behind this sub has been renamed since it linked,
        // so the credential now carries an address the row does not hold.
        User renamed = applicantRow(61L, "old@example.com", GOOGLE_SUB, true);
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.just(renamed));

        StepVerifier.create(authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)))
                .assertNext(response -> assertThat(response.getUserId()).isEqualTo(61L))
                .verifyComplete();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        // Leaving the two to drift is the deliberate choice, and this is the
        // guard on it: adopting the credential's address instead would let a
        // Google rename move an account onto an address someone else already
        // holds here, which is the hijack again from the other end. users.email
        // is unique, so it would also collide rather than fail safe.
        assertThat(saved.getValue().getEmail()).isEqualTo("old@example.com");
    }

    @Test
    @DisplayName("a row already linked to a different Google identity is not re-pointed")
    void refusesToStealAnotherGoogleIdentitysRow() {
        User boundElsewhere = applicantRow(31L, VICTIM_EMAIL, OTHER_GOOGLE_SUB, true);
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.empty());
        when(userRepository.findByEmail(VICTIM_EMAIL)).thenReturn(Mono.just(boundElsewhere));

        StepVerifier.create(authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)))
                .verifyError(UnauthorizedException.class);

        // A verified row is otherwise linkable, so the sub already on it is the
        // only thing refusing this one -- and it has to stay where it is.
        assertThat(boundElsewhere.getGoogleSubject()).isEqualTo(OTHER_GOOGLE_SUB);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("an address with no account gets one, carrying the sub")
    void createsAnAccountAndStoresTheSub() {
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.empty());
        when(userRepository.findByEmail(VICTIM_EMAIL)).thenReturn(Mono.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User created = invocation.getArgument(0);
            created.setId(41L);
            return Mono.just(created);
        });

        AuthResponse response = authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)).block();

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("Google account created");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        // Without this the account it just created is unreachable by sub, so the
        // next sign-in falls back to the address and the whole rule is decorative.
        assertThat(saved.getValue().getGoogleSubject()).isEqualTo(GOOGLE_SUB);
        assertThat(saved.getValue().getEmail()).isEqualTo(VICTIM_EMAIL);
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.APPLICANT);
        assertThat(saved.getValue().isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("a verified account is linked on first Google sign-in")
    void linksAnAlreadyVerifiedAccount() {
        // No such row exists yet -- registerWithInvitation is the only other
        // writer of emailVerified true and it is unreachable, because it matches
        // on users.invitation_token and nothing writes that column. So this is
        // the shape of a row created here through Google on an earlier deploy,
        // and the case is what stops the rule from locking those out later.
        User verified = applicantRow(51L, VICTIM_EMAIL, null, true);
        when(userRepository.findByGoogleSubject(GOOGLE_SUB)).thenReturn(Mono.empty());
        when(userRepository.findByEmail(VICTIM_EMAIL)).thenReturn(Mono.just(verified));

        StepVerifier.create(authService.loginWithGoogle(new GoogleAuthRequest(CREDENTIAL)))
                .assertNext(response -> assertThat(response.getUserId()).isEqualTo(51L))
                .verifyComplete();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        // The link is written on the way through, so the second sign-in matches
        // on the sub and never consults the address again.
        assertThat(saved.getValue().getGoogleSubject()).isEqualTo(GOOGLE_SUB);
    }

    private User applicantRow(Long id, String email, String googleSubject, boolean emailVerified) {
        return User.builder()
                .id(id)
                .email(email)
                .googleSubject(googleSubject)
                .passwordHash("hashed")
                .firstName("Ada")
                .lastName("Lovelace")
                .organizationId(null)
                .role(UserRole.APPLICANT)
                .isPlatformAdmin(false)
                .isActive(true)
                .emailVerified(emailVerified)
                .tokenVersion(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
