package com.airral.service;

import com.airral.domain.User;
import com.airral.exception.BadRequestException;
import com.airral.exception.EmailNotVerifiedException;
import com.airral.exception.UnauthorizedException;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    public AccountVerificationService(FirebaseIdentityService firebaseIdentityService,
                                      UserRepository userRepository,
                                      PasswordEncoder passwordEncoder,
                                      TokenVersionCache tokenVersionCache,
                                      LoginThrottle loginThrottle,
                                      CompanyVerificationService companyVerificationService) {
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
}
