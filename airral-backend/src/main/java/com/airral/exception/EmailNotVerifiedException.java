package com.airral.exception;

import org.springframework.http.HttpStatus;

/**
 * An action that needs a proven email address, asked for by an account that has
 * not proven one.
 *
 * <p>403 with error {@code EMAIL_NOT_VERIFIED}, so the portals can tell this
 * apart from every other refusal and show "check your inbox" with a resend
 * button instead of a generic error.
 */
public class EmailNotVerifiedException extends ApiException {

    public static final String ERROR = "EMAIL_NOT_VERIFIED";

    public EmailNotVerifiedException(String action) {
        super(HttpStatus.FORBIDDEN, ERROR,
                "Verify your email address to " + action + ". We sent a link to your inbox when you signed up.");
    }
}
