package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.TransactionDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emails wait for the change they describe to be saved. Runs the real
 * reactive transaction machinery over a transaction manager with no database.
 */
class AfterCommitTest {

    private final List<String> happened = new ArrayList<>();
    private final TransactionalOperator transactionally = TransactionalOperator.create(new NoDatabase());

    private Mono<Void> saveThen(String what) {
        return Mono.fromRunnable(() -> happened.add("saved"))
                .then(AfterCommit.run(() -> happened.add(what)))
                .then(Mono.fromRunnable(() -> happened.add("more work")));
    }

    @Test
    @DisplayName("inside a transaction, the email goes after the commit")
    void afterTheCommit() {
        StepVerifier.create(transactionally.transactional(saveThen("email"))).verifyComplete();

        assertThat(happened).containsExactly("saved", "more work", "committed", "email");
    }

    @Test
    @DisplayName("when the transaction rolls back, the email never goes")
    void notOnRollback() {
        StepVerifier.create(transactionally.transactional(saveThen("email")
                        .then(Mono.error(new IllegalStateException("a later step failed")))))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(happened).containsExactly("saved", "more work", "rolled back");
    }

    @Test
    @DisplayName("outside a transaction, the email goes at once")
    void withoutATransaction() {
        StepVerifier.create(saveThen("email")).verifyComplete();

        assertThat(happened).containsExactly("saved", "email", "more work");
    }

    @Test
    @DisplayName("an email that fails after the commit does not fail the request that committed")
    void failureAfterCommitIsNotTheRequests() {
        StepVerifier.create(transactionally.transactional(
                        AfterCommit.run(() -> { throw new IllegalStateException("SMTP is down"); })))
                .verifyComplete();
    }

    /** Begins, commits and rolls back nothing but a note of it. */
    private final class NoDatabase extends AbstractReactiveTransactionManager {
        @Override
        protected Object doGetTransaction(TransactionSynchronizationManager synchronizationManager) {
            return new Object();
        }

        @Override
        protected Mono<Void> doBegin(TransactionSynchronizationManager synchronizationManager, Object transaction,
                                     TransactionDefinition definition) {
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doCommit(TransactionSynchronizationManager synchronizationManager,
                                      GenericReactiveTransaction status) {
            return Mono.fromRunnable(() -> happened.add("committed"));
        }

        @Override
        protected Mono<Void> doRollback(TransactionSynchronizationManager synchronizationManager,
                                        GenericReactiveTransaction status) {
            return Mono.fromRunnable(() -> happened.add("rolled back"));
        }
    }
}
