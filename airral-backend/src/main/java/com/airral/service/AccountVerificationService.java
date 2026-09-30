package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.domain.UserInvitation;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.EmailLinkRateLimitedException;
import com.airral.exception.EmailNotVerifiedException;
import com.airral.exception.UnauthorizedException;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import java.util.Locale;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Everything that depends on a person having proven they own their address.
 *
 * <p>The proof itself is a Firebase ID token (see {@link FirebaseIdentityService}):
 * the browser only holds one after following a link Firebase emailed to that
 * address. AIRRAL never sends that mail, never sees the link's secret, and never
 * stores anything of Firebase's -- it reads the signed token, finds its own
 * account for the address, and records the fact.
 *
 * <p>Password reset uses the same proof and follows OWASP's reset guidance: the
 * new password is set only after the address is proven, no session is handed
 * back (the person signs in again), and every existing session for the account
 * is revoked.
 */
@Service
public class AccountVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AccountVerificationService.class);

    private final FirebaseIdentityService firebaseIdentityService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionCache tokenVersionCache;
    private final LoginThrottle loginThrottle;
    private final CompanyVerificationService companyVerificationService;
    private final FirebaseEmailLinkSender linkSender;
    private final UserInvitationRepository invitationRepository;

    /**
     * Every forgot-password answer takes at least this long, whether or not a
     * link was sent. Sending takes a network round trip to Firebase and not
     * sending takes nothing, so without a floor the delay alone would say which
     * addresses have accounts. The portal shows a loader over it.
     */
    static final Duration FORGOT_PASSWORD_MIN_RESPONSE = Duration.ofMillis(2500);

    public AccountVerificationService(FirebaseIdentityService firebaseIdentityService,
                                      UserRepository userRepository,
                                      PasswordEncoder passwordEncoder,
                                      TokenVersionCache tokenVersionCache,
                                      LoginThrottle loginThrottle,
                                      CompanyVerificationService companyVerificationService,
                                      FirebaseEmailLinkSender linkSender,
                                      UserInvitationRepository invitationRepository) {
        this.linkSender = linkSender;
        this.invitationRepository = invitationRepository;
        this.firebaseIdentityService = firebaseIdentityService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenVersionCache = tokenVersionCache;
        this.loginThrottle = loginThrottle;
        this.companyVerificationService = companyVerificationService;
    }

    public record VerifyResult(boolean verified, String email, String message) {}

    /**
     * Marks the account at the token's address as verified.
     *
     * <p>Answers "no account" plainly when there is none. That is not an
     * enumeration leak: only whoever controls the inbox can hold this token, and
     * telling someone whether their own address has an account is no secret from
     * them.
     */
    public Mono<VerifyResult> verifyEmail(String idToken) {
        return firebaseIdentityService.verifyIdToken(idToken)
                .flatMap(proof -> userRepository.findByEmail(proof.email())
                        .flatMap(user -> {
                            if (!user.isActive()) {
                                return Mono.<VerifyResult>error(new UnauthorizedException("Account is deactivated"));
                            }
                            boolean already = user.isEmailVerified();
                            markVerified(user);
                            return userRepository.save(user)
                                    .flatMap(saved -> companyVerificationService.onEmailProven(saved).thenReturn(saved))
                                    .doOnNext(saved -> log.info("Email verified for user {}{}", saved.getId(),
                                            already ? " (already verified)" : ""))
                                    .thenReturn(new VerifyResult(true, proof.email(), "Your email address is verified."));
                        })
                        .defaultIfEmpty(new VerifyResult(false, proof.email(),
                                "No AIRRAL account uses " + proof.email() + ". Sign up with it first.")));
    }

    /**
     * Sets a new password for the account at the token's address, once that
     * address has been proven. Also verifies the address -- following the link
     * is the proof -- and records the password as set by the proven owner.
     */
    public Mono<Void> resetPassword(String idToken, String newPassword) {
        return firebaseIdentityService.verifyIdToken(idToken)
                .flatMap(proof -> userRepository.findByEmail(proof.email())
                        .switchIfEmpty(Mono.error(new BadRequestException(
                                "No AIRRAL account uses " + proof.email() + ". Sign up with it instead.")))
                        .flatMap(user -> {
                            if (!user.isActive()) {
                                return Mono.<User>error(new UnauthorizedException("Account is deactivated"));
                            }
                            user.setPasswordHash(passwordEncoder.encode(newPassword));
                            user.setPasswordProvenAt(LocalDateTime.now());
                            markVerified(user);
                            return userRepository.save(user);
                        }))
                .flatMap(user -> tokenVersionCache.revokeAll(user.getId())
                        // Whoever just proved they own the inbox should not stay
                        // locked out by failed attempts from before they did.
                        .then(loginThrottle.recordSuccess(user.getEmail()))
                        .then(companyVerificationService.onEmailProven(user))
                        .doOnSuccess(ignored -> log.info("Password reset completed for user {}", user.getId())))
                .then();
    }

    /**
     * Send a reset link if, and only if, the address has an active account.
     *
     * <p>Completes the same way either way, after the same delay: the caller may
     * be anyone, and "no account" must not be something they can learn. Nothing
     * that goes wrong here reaches them either -- an error only real accounts can
     * produce would be the same leak by another route.
     */
    public Mono<Void> requestPasswordReset(String email) {
        String address = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        Mono<Void> work = userRepository.findByEmail(address)
                .filter(User::isActive)
                .flatMap(user -> sendWithinLimit(user, FirebaseEmailLinkSender.Purpose.RESET))
                .onErrorResume(error -> {
                    log.warn("Password reset link not sent: {}", error.toString());
                    return Mono.empty();
                })
                .then();
        return Mono.when(work, Mono.delay(FORGOT_PASSWORD_MIN_RESPONSE));
    }

    public enum VerificationSend { SENT, ALREADY_VERIFIED }

    /**
     * Send a verification link to the signed-in account's own address. The owner
     * is asking about their own account, so unlike forgot-password this can say
     * plainly what happened -- including that they have hit the limit.
     */
    public Mono<VerificationSend> sendVerification(Long userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new UnauthorizedException("Account not found")))
                .flatMap(user -> {
                    if (user.isEmailVerified()) {
                        return Mono.just(VerificationSend.ALREADY_VERIFIED);
                    }
                    return loginThrottle.emailLinkAllowed(user.getId())
                            .flatMap(allowed -> allowed
                                    ? loginThrottle.recordEmailLink(user.getId())
                                            .then(linkSender.send(user, FirebaseEmailLinkSender.Purpose.VERIFY))
                                            .thenReturn(VerificationSend.SENT)
                                    : Mono.<VerificationSend>error(new EmailLinkRateLimitedException()));
                });
    }

    /**
     * The link every new password account gets straight after signing up. Never
     * allowed to fail the sign-up: if it does not go out, the banner's "Resend
     * link" is there.
     */
    public Mono<Void> sendVerificationAfterSignup(Long userId) {
        return sendVerification(userId)
                .onErrorResume(error -> {
                    log.warn("Verification link after sign-up not sent for user {}: {}", userId, error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> sendWithinLimit(User user, FirebaseEmailLinkSender.Purpose purpose) {
        return loginThrottle.emailLinkAllowed(user.getId())
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.info("{} link for user {} not sent: limit reached", purpose, user.getId());
                        return Mono.<Void>empty();
                    }
                    return loginThrottle.recordEmailLink(user.getId()).then(linkSender.send(user, purpose));
                });
    }

    /** Fails with 403 EMAIL_NOT_VERIFIED unless the account has proven its address. */
    public Mono<Void> requireVerified(Long userId, String action) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new UnauthorizedException("Account not found")))
                .flatMap(user -> user.isEmailVerified()
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new EmailNotVerifiedException(action)));
    }

    private static void markVerified(User user) {
        if (!user.isEmailVerified() || user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        user.setEmailVerified(true);
        user.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Accept an invitation: the invitee followed the link Firebase emailed and
     * now sets a password.
     *
     * <p>The Firebase ID token proves they own the address the invitation was
     * sent to, so HR, who never sees the invitation's token, cannot accept on
     * their behalf. The account gets the invitation's company and role, starts
     * verified, and the invitation cannot be used again.
     */
    public Mono<User> acceptInvitation(String invitationToken, String idToken, String password,
                                       String firstName, String lastName) {
        return firebaseIdentityService.verifyIdToken(idToken)
                .flatMap(proof -> invitationRepository.findByInvitationToken(invitationToken)
                        .filter(UserService::isOpen)
                        .switchIfEmpty(Mono.error(new BadRequestException(UserService.INVITATION_GONE)))
                        .flatMap(invitation -> {
                            if (!invitation.getEmail().equalsIgnoreCase(proof.email())) {
                                return Mono.<User>error(new BadRequestException(
                                        "This invitation was sent to a different address. Open it from the email it was sent to."));
                            }
                            if (!UserService.INVITABLE_ROLES.contains(invitation.getRole())) {
                                return Mono.<User>error(new BadRequestException(UserService.INVITATION_GONE));
                            }
                            String email = invitation.getEmail().trim().toLowerCase(Locale.ROOT);
                            return inviterStillHires(invitation)
                                    .then(userRepository.existsByEmail(email))
                                    .flatMap(exists -> exists
                                            ? Mono.<User>error(new ConflictException(
                                                    "That address already has an AIRRAL account. Sign in instead."))
                                            : userRepository.save(invitedUser(invitation, email, password, firstName, lastName)))
                                    .flatMap(user -> {
                                        invitation.setIsAccepted(true);
                                        invitation.setAcceptedAt(LocalDateTime.now());
                                        return invitationRepository.save(invitation).thenReturn(user);
                                    });
                        }))
                // Not onEmailProven: an invitee proving their own address says nothing
                // about whether the company that invited them is who it claims to be,
                // so accepting must never approve a company by its email domain.
                .flatMap(user -> loginThrottle.recordSuccess(user.getEmail())
                        .doOnSuccess(ignored -> log.info("Invitation accepted: user {} joined company {}",
                                user.getId(), user.getOrganizationId()))
                        .thenReturn(user));
    }

    /**
     * An invitation stands only while whoever sent it can still invite: an HR
     * manager (or AIRRAL admin) of that company whose account is on. One sent
     * by someone since switched off or moved out of HR no longer lets anyone in.
     */
    private Mono<Void> inviterStillHires(UserInvitation invitation) {
        if (invitation.getInvitedById() == null) {
            return Mono.error(new BadRequestException(UserService.INVITATION_GONE));
        }
        return userRepository.findById(invitation.getInvitedById())
                .filter(inviter -> Boolean.TRUE.equals(inviter.getIsActive())
                        && invitation.getOrganizationId().equals(inviter.getOrganizationId())
                        && (inviter.getRole() == UserRole.HR_MANAGER || inviter.getRole() == UserRole.ADMIN))
                .switchIfEmpty(Mono.error(new BadRequestException(UserService.INVITATION_GONE)))
                .then();
    }

    private User invitedUser(UserInvitation invitation, String email, String password,
                             String firstName, String lastName) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(StringUtils.hasText(firstName) ? firstName.trim() : invitation.getFirstName())
                .lastName(StringUtils.hasText(lastName) ? lastName.trim() : invitation.getLastName())
                .organizationId(invitation.getOrganizationId())
                .role(invitation.getRole())
                .department(invitation.getDepartment())
                .departmentId(invitation.getDepartmentId())
                .isPlatformAdmin(false)
                .isActive(true)
                .emailVerified(true)
                .emailVerifiedAt(now)
                .passwordProvenAt(now)
                .createdById(invitation.getInvitedById())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
