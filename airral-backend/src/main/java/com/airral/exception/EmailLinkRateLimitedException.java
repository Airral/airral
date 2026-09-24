package com.airral.exception;

import org.springframework.http.HttpStatus;

/** Too many verification or reset links for one account in a short time. 429. */
public class EmailLinkRateLimitedException extends ApiException {

    public EmailLinkRateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_LINK_RATE_LIMITED",
                "We've sent several links in the last few minutes. Check your inbox and spam folder, "
                        + "or try again in 15 minutes.");
    }
}
