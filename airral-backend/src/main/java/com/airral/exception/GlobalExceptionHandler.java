package com.airral.exception;

import com.airral.config.ServerErrorAlertFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleApiException(ApiException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", ex.getStatus().value());
        errorResponse.put("error", ex.getError());
        errorResponse.put("message", ex.getMessage());

        return Mono.just(ResponseEntity.status(ex.getStatus()).body(errorResponse));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Bad Request");
        errorResponse.put("message", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidationException(WebExchangeBindException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Validation Failed");
        
        Map<String, String> validationErrors = new HashMap<>();
        ex.getFieldErrors().forEach(error -> 
            validationErrors.put(error.getField(), error.getDefaultMessage())
        );
        
        errorResponse.put("validationErrors", validationErrors);
        // Clients show "message"; without one they fall back to a raw HTTP error.
        ex.getFieldErrors().stream().findFirst()
                .ifPresent(error -> errorResponse.put("message", error.getDefaultMessage()));
        
        return Mono.just(ResponseEntity.badRequest().body(errorResponse));
    }

    /**
     * Exceptions that already carry their own status.
     *
     * <p>Without this the RuntimeException catch-all below claims them --
     * ResponseStatusException is a RuntimeException -- and a deliberate 429 or
     * 404 is reported as an Internal Server Error. That is exactly what
     * happened to login throttling: the limit fired correctly and the caller
     * was told the server had broken.
     *
     * <p>Spring prefers the most specific handler, so declaring this is enough
     * to take precedence.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleResponseStatusException(
            ResponseStatusException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", ex.getStatusCode().value());
        errorResponse.put("error", HttpStatus.valueOf(ex.getStatusCode().value()).getReasonPhrase());
        errorResponse.put("message", ex.getReason());
        errorResponse.put("timestamp", LocalDateTime.now());
        // Headers matter here: Retry-After is the only actionable part of a 429.
        return Mono.just(ResponseEntity.status(ex.getStatusCode())
                .headers(ex.getHeaders())
                .body(errorResponse));
    }

    /**
     * A refused authorization is the caller's fault, not the server's.
     *
     * <p>The accessDeniedHandler wired up in SecurityConfig only ever sees
     * denials raised in the filter chain. A @PreAuthorize denial is thrown
     * inside the handler method, long past that point, so the catch-all below
     * claimed it -- AccessDeniedException is a RuntimeException -- and every
     * legitimate role refusal was answered and recorded as a 500. On airral-api
     * that is the bulk of the 5xx rate, which is enough to bury a real fault.
     *
     * <p>The message is fixed rather than lifted off the exception. Spring's
     * text names the expression that denied the call, and handing that to
     * whoever is probing describes the authorization rules to them.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleAccessDeniedException(AccessDeniedException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.FORBIDDEN.value());
        errorResponse.put("error", "Forbidden");
        errorResponse.put("message", "You do not have permission to perform this action");

        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse));
    }

    /**
     * Two saves of the same thing at once, settled by the database: a failed
     * version check (the row changed since it was read), or a duplicate of
     * something only one may exist of, such as a candidate's open offer. The
     * caller lost the race; that is a conflict to reload from, not a fault.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, DuplicateKeyException.class})
    public Mono<ResponseEntity<Map<String, Object>>> handleConcurrentChange(RuntimeException ex, ServerWebExchange exchange) {
        log.info("{} on {} {}", ex.getClass().getSimpleName(), exchange.getRequest().getMethod(),
                ServerErrorAlertFilter.routeOf(exchange));
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.CONFLICT.value());
        errorResponse.put("error", "Conflict");
        errorResponse.put("message", "This was changed at the same moment somewhere else. Reload the page and try again.");
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse));
    }

    @ExceptionHandler(RuntimeException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleRuntimeException(RuntimeException ex, ServerWebExchange exchange) {
        return serverError(ex, exchange, "Internal Server Error");
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGenericException(Exception ex, ServerWebExchange exchange) {
        return serverError(ex, exchange, "Unexpected Error");
    }

    /**
     * A failure nobody planned for. The exception went unlogged and its message
     * went to the caller, which could be anything from an SQL error to a null
     * pointer. Now it is logged with a short reference, the caller gets a plain
     * message with that reference, and ServerErrorAlertFilter can name it in its
     * Slack alert.
     */
    private Mono<ResponseEntity<Map<String, Object>>> serverError(Exception ex, ServerWebExchange exchange, String error) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.error("{} on {} {} (ref {})", ex.getClass().getSimpleName(), exchange.getRequest().getMethod(),
                ServerErrorAlertFilter.routeOf(exchange), reference, ex);
        exchange.getAttributes().put(ServerErrorAlertFilter.ERROR_TYPE, ex.getClass().getSimpleName());
        exchange.getAttributes().put(ServerErrorAlertFilter.ERROR_REFERENCE, reference);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        errorResponse.put("error", error);
        errorResponse.put("message", "Something went wrong on our side. Try again, and if it keeps happening, "
                + "email contact@airral.com with reference " + reference + ".");
        errorResponse.put("reference", reference);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
    }
}
