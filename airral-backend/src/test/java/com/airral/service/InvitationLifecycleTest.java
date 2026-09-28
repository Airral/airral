package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.UserInvitation;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.InviteUserRequest;
import com.airral.dto.response.InvitationResponse;
import com.airral.exception.InvitationRateLimitedException;
import com.airral.exception.NotFoundException;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An invitation from the moment HR sends it until it is used or withdrawn.
 * Runs the real UserService over mocked repositories and a mocked email sender.
 */
class InvitationLifecycleTest {

    private static final long ACME = 1L;

    private final UserRepository users = mock(UserRepository.class);
    private final UserInvitationRepository invitations = mock(UserInvitationRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final FirebaseEmailLinkSender linkSender = mock(FirebaseEmailLinkSender.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, invitations, organizations, mock(DepartmentRepository.class), linkSender, throttle);
        when(users.findByEmail(any())).thenReturn(Mono.empty());
        when(invitations.findValidInvitationByEmailAndOrganization(any(), any())).thenReturn(Mono.empty());
        when(invitations.save(any(UserInvitation.class))).thenAnswer(inv -> {
            UserInvitation saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(30L);
            }
            return Mono.just(saved);
        });
        when(throttle.invitationEmailAllowed(ACME)).thenReturn(Mono.just(true));
        when(throttle.recordInvitationEmail(ACME)).thenReturn(Mono.empty());
        when(linkSender.sendInvitation(any(), any(), any())).thenReturn(Mono.empty());
    }

    private static InviteUserRequest invite(String email) {
        return InviteUserRequest.builder().email(email).role(UserRole.EMPLOYEE).firstName("Ben").build();
    }

    private static UserInvitation pending(long id, long company, LocalDateTime expiresAt) {
        return UserInvitation.builder().id(id).organizationId(company).email("ben@acme.io").role(UserRole.EMPLOYEE)
                .invitationToken("tok-" + id).expiresAt(expiresAt).isAccepted(false).build();
    }

    @Test
    @DisplayName("inviting someone emails them the link, and the response never carries its token")
    void inviteSendsTheEmail() {
        StepVerifier.create(service.inviteUser(invite("Ben@Acme.io"), ACME, 7L))
                .assertNext(response -> {
                    assertThat(response.getEmailSent()).isTrue();
                    assertThat(response.getEmail()).isEqualTo("ben@acme.io");
                })
                .verifyComplete();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(linkSender).sendInvitation(eq(30L), eq("ben@acme.io"), token.capture());
        assertThat(token.getValue()).isNotBlank();
        assertThat(Arrays.stream(InvitationResponse.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("invitationToken");
    }

    @Test
    @DisplayName("an invitation whose email fails is kept, and the response says the email did not go out")
    void failedEmailKeepsTheInvitation() {
        when(linkSender.sendInvitation(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("Firebase is down")));

        StepVerifier.create(service.inviteUser(invite("ben@acme.io"), ACME, 7L))
                .assertNext(response -> assertThat(response.getEmailSent()).isFalse())
                .verifyComplete();
        verify(invitations).save(any());
    }

    @Test
    @DisplayName("a company over its invitation budget is told to wait, and nothing is saved or sent")
    void budgetExhausted() {
        when(throttle.invitationEmailAllowed(ACME)).thenReturn(Mono.just(false));

        StepVerifier.create(service.inviteUser(invite("ben@acme.io"), ACME, 7L))
                .expectError(InvitationRateLimitedException.class)
                .verify();
        verify(invitations, never()).save(any());
        verify(linkSender, never()).sendInvitation(any(), any(), any());
    }

    @Test
    @DisplayName("resending gives the invitation a fresh week and emails it again")
    void resendExtendsAndSends() {
        UserInvitation invitation = pending(30L, ACME, LocalDateTime.now().plusHours(1));
        when(invitations.findById(30L)).thenReturn(Mono.just(invitation));

        StepVerifier.create(service.resendInvitation(30L, ACME))
                .assertNext(response -> assertThat(response.getEmailSent()).isTrue())
                .verifyComplete();

        assertThat(invitation.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
        verify(linkSender).sendInvitation(30L, "ben@acme.io", "tok-30");
    }

    @Test
    @DisplayName("an accepted invitation cannot be resent")
    void acceptedCannotBeResent() {
        UserInvitation invitation = pending(30L, ACME, LocalDateTime.now().plusDays(1));
        invitation.setIsAccepted(true);
        when(invitations.findById(30L)).thenReturn(Mono.just(invitation));

        StepVerifier.create(service.resendInvitation(30L, ACME)).expectError(NotFoundException.class).verify();
        verify(linkSender, never()).sendInvitation(any(), any(), any());
    }

    @Test
    @DisplayName("cancelling removes a pending invitation")
    void cancelDeletes() {
        UserInvitation invitation = pending(30L, ACME, LocalDateTime.now().plusDays(1));
        when(invitations.findById(30L)).thenReturn(Mono.just(invitation));
        when(invitations.delete(invitation)).thenReturn(Mono.empty());

        StepVerifier.create(service.cancelInvitation(30L, ACME)).verifyComplete();
        verify(invitations).delete(invitation);
    }

    @Test
    @DisplayName("a company cannot cancel another company's invitation")
    void cannotCancelAnotherCompanysInvitation() {
        when(invitations.findById(31L)).thenReturn(Mono.just(pending(31L, 2L, LocalDateTime.now().plusDays(1))));

        StepVerifier.create(service.cancelInvitation(31L, ACME)).expectError(NotFoundException.class).verify();
        verify(invitations, never()).delete(any(UserInvitation.class));
    }

    @Test
    @DisplayName("an open invitation's page shows the address and the company")
    void describeOpenInvitation() {
        when(invitations.findByInvitationToken("tok-30"))
                .thenReturn(Mono.just(pending(30L, ACME, LocalDateTime.now().plusDays(1))));
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));

        StepVerifier.create(service.describeInvitation("tok-30"))
                .assertNext(preview -> {
                    assertThat(preview.getEmail()).isEqualTo("ben@acme.io");
                    assertThat(preview.getCompanyName()).isEqualTo("Acme");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an expired invitation's page says it can no longer be used")
    void describeExpiredInvitation() {
        when(invitations.findByInvitationToken("tok-30"))
                .thenReturn(Mono.just(pending(30L, ACME, LocalDateTime.now().minusMinutes(1))));

        StepVerifier.create(service.describeInvitation("tok-30")).expectError(NotFoundException.class).verify();
    }

    @Test
    @DisplayName("an invitation's email link lands on the HR portal's page for that invitation")
    void invitationLinkLandsOnTheAcceptPage() {
        FirebaseEmailLinkSender sender = new FirebaseEmailLinkSender(WebClient.builder(), "airral-test",
                "https://apply.airral.com", "https://app.airral.com/", false, false);

        assertThat(sender.invitationUrl("abc-123")).isEqualTo("https://app.airral.com/accept-invitation/abc-123");
    }
}
