package com.airral.dto.response;

/**
 * The outcome of an unsubscribe attempt.
 *
 * <p>{@code unsubscribed} is false when the token matched no row, so the page
 * can say that plainly instead of reporting a success that did not happen.
 */
public record UnsubscribeResultResponse(boolean unsubscribed, String message) {
}
