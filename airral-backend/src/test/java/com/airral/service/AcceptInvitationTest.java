package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.UserInvitation;
import com.airral.domain.enums.UserRole;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Accepting an invitation. The Firebase ID token stands for "this browser
 * followed the link Firebase emailed to this address"; everything else comes
 * from the invitation, not from the person accepting it.
 */
class AcceptInvitationTest {

    private final FirebaseIdentityService firebase = mock(FirebaseIdentityService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserInvitationRepository invitations = mock(UserInvitationRepository.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private final CompanyVerificationService companies = mock(CompanyVerificationService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AccountVerificationService service;
    private UserInvitation invitation;

    @BeforeEach
    void setUp() {
        service = new AccountVerificationService(firebase, users, encoder, mock(TokenVersionCache.class), throttle,
                companies, mock(FirebaseEmailLinkSender.class), invitations);
        invitation = UserInvitation.builder().id(30L).organizationId(1L).email("ben@acme.io").role(UserRole.MANAGER)
                .firstName("Ben").department("Engineering").invitedById(7L).invitationToken("tok")
                .expiresAt(LocalDateTime.now().plusDays(3)).isAccepted(false).build();
        when(invitations.findByInvitationToken("tok")).thenReturn(Mono.just(invitation));
        when(invitations.save(any(UserInvitation.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(users.existsByEmail("ben@acme.io")).thenReturn(Mono.just(false));
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            user.setId(40L);
            return Mono.just(user);
        });
        when(encoder.encode("Secret123")).thenReturn("hash");
        when(throttle.recordSuccess(any())).thenReturn(Mono.empty());
        when(companies.onEmailProven(any())).thenReturn(Mono.empty());
        when(users.findById(7L)).thenReturn(Mono.just(inviter));
    }

    /** Whoever sent the invitation: Acme's HR manager, account on. */
    private final User inviter = User.builder().id(7L).organizationId(1L).role(UserRole.HR_MANAGER)
            .isActive(true).emailVerified(true).build();

    private void linkFollowedBy(String email) {
        when(firebase.verifyIdToken("id-token"))
                .thenReturn(Mono.just(new FirebaseIdentityService.VerifiedEmail(email, "uid", Instant.now())));
    }

    private void refused(Class<? extends Throwable> error) {
        StepVerifier.create(service.acceptInvitation("tok", "id-token", "Secret123", null, null))
                .expectError(error)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("accepting creates a verified account with the invitation's company and role, and uses the invitation up")
    void acceptCreatesTheAccount() {
        linkFollowedBy("Ben@Acme.io");

        StepVerifier.create(service.acceptInvitation("tok", "id-token", "Secret123", "Benjamin", null))
                .assertNext(user -> {
                    assertThat(user.getOrganizationId()).isEqualTo(1L);
                    assertThat(user.getRole()).isEqualTo(UserRole.MANAGER);
                    assertThat(user.getEmail()).isEqualTo("ben@acme.io");
                    assertThat(user.getFirstName()).isEqualTo("Benjamin");
                    assertThat(user.getDepartment()).isEqualTo("Engineering");
                    assertThat(user.isEmailVerified()).isTrue();
                    assertThat(user.getPasswordProvenAt()).isNotNull();
                    assertThat(user.getPasswordHash()).isEqualTo("hash");
                })
                .verifyComplete();

        assertThat(invitation.getIsAccepted()).isTrue();
        assertThat(invitation.getAcceptedAt()).isNotNull();
        // An invitee proving their own address says nothing about the company.
        verify(companies, never()).onEmailProven(any());
    }

    @Test
    @DisplayName("an invitation from someone whose account was since switched off no longer lets anyone in")
    void inviterSwitchedOff() {
        inviter.setIsActive(false);
        linkFollowedBy("ben@acme.io");

        refused(BadRequestException.class);
        assertThat(invitation.getIsAccepted()).isFalse();
    }

    @Test
    @DisplayName("an invitation from someone since moved out of HR no longer lets anyone in")
    void inviterMovedOutOfHr() {
        inviter.setRole(UserRole.EMPLOYEE);
        linkFollowedBy("ben@acme.io");

        refused(BadRequestException.class);
    }

    @Test
    @DisplayName("an invitation whose sender is gone no longer lets anyone in")
    void inviterGone() {
        when(users.findById(7L)).thenReturn(Mono.empty());
        linkFollowedBy("ben@acme.io");

        refused(BadRequestException.class);
    }

    @Test
    @DisplayName("a link followed from a different address cannot accept the invitation")
    void differentAddressIsRefused() {
        linkFollowedBy("someone-else@example.com");

        refused(BadRequestException.class);
        assertThat(invitation.getIsAccepted()).isFalse();
    }

    @Test
    @DisplayName("an invitation can be used only once")
    void acceptedInvitationIsRefused() {
        invitation.setIsAccepted(true);
        linkFollowedBy("ben@acme.io");

        refused(BadRequestException.class);
    }

    @Test
    @DisplayName("an expired invitation cannot be accepted")
    void expiredInvitationIsRefused() {
        invitation.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        linkFollowedBy("ben@acme.io");

        refused(BadRequestException.class);
    }

    @Test
    @DisplayName("an address that already has an account is told to sign in")
    void existingAccountIsRefused() {
        linkFollowedBy("ben@acme.io");
        when(users.existsByEmail("ben@acme.io")).thenReturn(Mono.just(true));

        refused(ConflictException.class);
    }

    @Test
    @DisplayName("an invitation for a role no company can give is refused, even one already stored")
    void adminInvitationIsRefused() {
        invitation.setRole(UserRole.ADMIN);
        linkFollowedBy("ben@acme.io");

        refused(BadRequestException.class);
    }
}
