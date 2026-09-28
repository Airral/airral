package com.airral.exception;

import org.springframework.http.HttpStatus;

/** Too many invitation emails from one company in a short time. 429. */
public class InvitationRateLimitedException extends ApiException {
    public InvitationRateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "INVITATION_RATE_LIMITED",
                "Your company has sent a lot of invitations in the last few minutes. Try again in 15 minutes.");
    }
}
