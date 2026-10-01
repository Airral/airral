package com.airral.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who may connect an AI assistant to AIRRAL by making their own API key.
 *
 * <p>A paid feature. Until plans exist it is switched on per account, by email,
 * in {@code airral.ai-access.allowed-emails} -- for now one account to test
 * with. When plans land, {@link #included} is the one place that changes:
 * employers gate on organizations.tier, which every request already carries.
 *
 * <p>Decided on the server from the user's row as it is now, never from what a
 * portal shows. A check that only hides a button is not a check, since the
 * endpoint is still reachable with a session token.
 */
@Component
public class AiAccessPolicy {

    /** Whether the feature is on for this account, and if not, why. */
    public record Decision(boolean included, boolean available, String reason, String message) {

        static Decision allowed() {
            return new Decision(true, true, null, null);
        }

        static Decision notIncluded(String reason, String message) {
            return new Decision(false, false, reason, message);
        }

        static Decision blocked(String reason, String message) {
            return new Decision(true, false, reason, message);
        }
    }

    private final Set<String> allowedEmails;

    public AiAccessPolicy(@Value("${airral.ai-access.allowed-emails:}") String allowedEmails) {
        // Commas, semicolons or spaces, so the value survives however a deploy
        // tool splits its environment variables.
        this.allowedEmails = Arrays.stream((allowedEmails == null ? "" : allowedEmails).split("[,;\\s]+"))
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Decision decide(ApiKeyStore.SelfServiceUser user) {
        if (user == null || !user.active()) {
            return Decision.notIncluded("ACCOUNT_INACTIVE", "This account is not active.");
        }
        // Admins never self-issue: an admin credential in a config file is the
        // one key worth stealing. An unknown role gets nothing, not a default.
        if (ApiKeyScopes.selfService(user.role()).isEmpty()) {
            return Decision.notIncluded("ROLE_NOT_ALLOWED",
                    "AI assistant keys are for applicant and company accounts.");
        }
        if (!included(user)) {
            return Decision.notIncluded("NOT_INCLUDED",
                    "Connecting an AI assistant is a paid feature, and it isn't on your account yet.");
        }
        if (!user.emailVerified()) {
            return Decision.blocked("EMAIL_NOT_VERIFIED",
                    "Verify your email address first. We sent a link to your inbox when you signed up.");
        }
        // The same bar a company's jobs need before candidates see them: a
        // company AIRRAL has not approved cannot reach AIRRAL through a key.
        if (user.organizationId() != null
                && (!"VERIFIED".equals(user.companyStatus()) || !user.companyActive())) {
            return Decision.blocked("COMPANY_NOT_VERIFIED",
                    "Your company needs to be approved by AIRRAL first. We review new companies within a day.");
        }
        return Decision.allowed();
    }

    private boolean included(ApiKeyStore.SelfServiceUser user) {
        return includes(user.email());
    }

    /**
     * Whether the paid feature is on for this address. Checked on every request
     * a self-made key makes, so taking the feature away stops its keys at once.
     */
    public boolean includes(String email) {
        return email != null && allowedEmails.contains(email.toLowerCase(Locale.ROOT));
    }
}
