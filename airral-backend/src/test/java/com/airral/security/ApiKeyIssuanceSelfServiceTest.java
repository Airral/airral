package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.ForbiddenException;
import com.airral.exception.UnauthorizedException;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Making your own key: what is decided for you, and what stops you. */
class ApiKeyIssuanceSelfServiceTest {

    private final ApiKeyStore store = mock(ApiKeyStore.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);
    private ApiKeyIssuanceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // The transaction itself is the database's business; here it passes through.
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ApiKeyIssuanceService(store, new AiAccessPolicy("hr@fieldline.test,maya@example.com"), transactions);
        when(store.insertSelfService(anyLong(), any(), anyString(), anyList(), anyString(), anyString(), anyString(),
                anyInt(), any(), anyInt())).thenReturn(Mono.just(1L));
        when(store.lockForIssuance(anyLong())).thenReturn(Mono.empty());
        when(store.countUsable(anyLong())).thenReturn(Mono.just(0L));
    }

    private void userIs(ApiKeyStore.SelfServiceUser user) {
        when(store.findSelfServiceUser(user.id())).thenReturn(Mono.just(user));
    }

    private static ApiKeyStore.SelfServiceUser hr() {
        return new ApiKeyStore.SelfServiceUser(91L, "hr@fieldline.test", "HR_MANAGER", 29L, true, true, "VERIFIED", true);
    }

    @Test
    @DisplayName("an HR manager gets a read-only key for their company, for 90 days, issued by themselves")
    void hrManagerKey() {
        userIs(hr());
        StepVerifier.create(service.issueForSelf(91L, "  My laptop  ", 4))
                .assertNext(issued -> {
                    assertTrue(issued.rawKey().startsWith("airral_ak_live_"));
                    assertEquals("My laptop", issued.name());
                    assertEquals(List.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_READ), issued.scopes());
                    assertEquals(60, issued.ratePerMinute());
                    assertNotNull(issued.expiresAt());
                    assertTrue(issued.expiresAt().isAfter(LocalDateTime.now().plusDays(89)));
                    assertTrue(issued.expiresAt().isBefore(LocalDateTime.now().plusDays(91)));
                })
                .verifyComplete();
        verify(store).lockForIssuance(91L);
        verify(store).insertSelfService(eq(91L), eq(29L), eq("HR_MANAGER"),
                eq(List.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_READ)), anyString(), anyString(),
                eq("My laptop"), eq(60), any(), eq(4));
        verify(transactions).transactional(any(Mono.class));
    }

    @Test
    @DisplayName("an applicant's key has no company and can only read jobs")
    void applicantKey() {
        userIs(new ApiKeyStore.SelfServiceUser(89L, "maya@example.com", "APPLICANT", null, true, true, null, true));
        StepVerifier.create(service.issueForSelf(89L, "Claude Code", 4))
                .assertNext(issued -> assertEquals(List.of(ApiKeyScopes.JOBS_READ), issued.scopes()))
                .verifyComplete();
        verify(store).insertSelfService(eq(89L), isNull(), eq("APPLICANT"), eq(List.of(ApiKeyScopes.JOBS_READ)),
                anyString(), anyString(), eq("Claude Code"), eq(60), any(), eq(4));
    }

    @Test
    @DisplayName("without the paid feature, no key is made")
    void notIncludedIsRefused() {
        userIs(new ApiKeyStore.SelfServiceUser(5L, "sam@example.com", "APPLICANT", null, true, true, null, true));
        StepVerifier.create(service.issueForSelf(5L, "Claude", 4))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ForbiddenException);
                    assertEquals("NOT_INCLUDED", ((ForbiddenException) error).getError());
                })
                .verify();
        verify(store, never()).insertSelfService(anyLong(), any(), anyString(), anyList(), anyString(), anyString(),
                anyString(), anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("a company waiting for approval gets no key")
    void pendingCompanyIsRefused() {
        userIs(new ApiKeyStore.SelfServiceUser(91L, "hr@fieldline.test", "HR_MANAGER", 29L, true, true, "PENDING", true));
        StepVerifier.create(service.issueForSelf(91L, "Claude", 4))
                .expectErrorSatisfies(error -> assertEquals("COMPANY_NOT_VERIFIED",
                        ((ForbiddenException) error).getError()))
                .verify();
    }

    @Test
    @DisplayName("three working keys is the most")
    void capIsEnforced() {
        userIs(hr());
        when(store.countUsable(91L)).thenReturn(Mono.just(3L));
        StepVerifier.create(service.issueForSelf(91L, "Fourth", 4))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("a key needs a short, ordinary name")
    void nameIsChecked() {
        userIs(hr());
        StepVerifier.create(service.issueForSelf(91L, " ", 4)).expectError(BadRequestException.class).verify();
        StepVerifier.create(service.issueForSelf(91L, "x".repeat(81), 4)).expectError(BadRequestException.class).verify();
        StepVerifier.create(service.issueForSelf(91L, "bad\nname", 4)).expectError(BadRequestException.class).verify();
    }

    @Test
    @DisplayName("revoking goes through the owner-scoped query")
    void revokeIsOwnerScoped() {
        when(store.revokeOwned("abcd1234", 91L, "Revoked by its owner")).thenReturn(Mono.just(1L));
        StepVerifier.create(service.revokeOwn(91L, "abcd1234")).expectNext(true).verifyComplete();
        when(store.revokeOwned("abcd1234", 92L, "Revoked by its owner")).thenReturn(Mono.just(0L));
        StepVerifier.create(service.revokeOwn(92L, "abcd1234")).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("a session revoked a moment ago cannot leave a key behind")
    void staleSessionMakesNoKey() {
        userIs(hr());
        // The conditional insert found the session's version out of date.
        when(store.insertSelfService(anyLong(), any(), anyString(), anyList(), anyString(), anyString(), anyString(),
                anyInt(), any(), anyInt())).thenReturn(Mono.empty());
        StepVerifier.create(service.issueForSelf(91L, "Claude", 3))
                .expectError(UnauthorizedException.class)
                .verify();
    }
}
