package com.airral.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A valid API key that has spent its budget for the current minute.
 *
 * <p>Extends {@link ResponseStatusException} rather than {@link ApiException},
 * which is what every other error here uses. The reason is where it is thrown:
 * rate limiting happens while the security context is being loaded, before a
 * handler has been resolved, so {@code GlobalExceptionHandler} -- a
 * {@code @RestControllerAdvice} -- never sees it and the caller would get a 500.
 * WebFlux handles {@code ResponseStatusException} wherever it is raised,
 * including from a filter.
 *
 * <p>Distinct from an authentication failure on purpose. The credential is
 * good; answering 401 would tell an agent to go and fetch a new key, which does
 * not help and makes the load worse. 429 with {@code Retry-After} tells it the
 * one useful thing: wait.
 */
public class ApiKeyRateLimitExceededException extends ResponseStatusException {

    private static final long RETRY_AFTER_SECONDS = 60;

    public ApiKeyRateLimitExceededException(int limitPerMinute) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit of " + limitPerMinute + " requests per minute exceeded for this API key");
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // The window is a whole minute, so the longest a caller could need to
        // wait is 60 seconds. Reporting the true remainder would need the window
        // start threaded through, and would only ever be smaller than this.
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        return headers;
    }
}
