package com.airral.exception;

import org.springframework.http.HttpStatus;

/**
 * A signed-in person asking for something their account may not do yet.
 *
 * <p>403 with a stable error code, so a portal can tell "not on your plan" from
 * "verify your email first" and show the right next step.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String error, String message) {
        super(HttpStatus.FORBIDDEN, error, message);
    }
}
