package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/**
 * Runs something once the surrounding transaction has committed, or at once
 * when there is none. Emails go out this way: a message saying an offer was
 * sent or an interview booked must not leave when the change it describes is
 * rolled back.
 */
final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    static Mono<Void> run(Runnable action) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(transaction -> {
                    if (!transaction.isSynchronizationActive()) {
                        return safely(action);
                    }
                    transaction.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public Mono<Void> afterCommit() {
                            return safely(action);
                        }
                    });
                    return Mono.<Void>empty();
                })
                .onErrorResume(NoTransactionException.class, none -> safely(action));
    }

    /** What runs after the commit cannot undo it, so a failure is logged rather than reported as the request's. */
    private static Mono<Void> safely(Runnable action) {
        return Mono.fromRunnable(action)
                .onErrorResume(error -> {
                    log.warn("A follow-up to a saved change failed: {}", error.toString());
                    return Mono.empty();
                })
                .then();
    }
}
