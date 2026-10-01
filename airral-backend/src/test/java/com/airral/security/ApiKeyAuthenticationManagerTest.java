package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Mono;

class ApiKeyAuthenticationManagerTest {

    private static final String KEY = "airral_ak_live_abcd1234_" + "x".repeat(43);

    private static ApiKeyStore.ResolvedKey key(String email, String role, List<String> scopes, boolean selfService) {
        return new ApiKeyStore.ResolvedKey(1L, 91L, email, 29L, "QUICK_HIRE", role, scopes, false, 60, selfService);
    }

    private static Authentication run(ApiKeyStore store, AiAccessPolicy policy) {
        return new ApiKeyAuthenticationManager(store, policy)
                .authenticate(new UsernamePasswordAuthenticationToken(KEY, KEY))
                .block();
    }

    private static ApiKeyStore storeResolving(ApiKeyStore.ResolvedKey resolved) {
        ApiKeyStore store = mock(ApiKeyStore.class);
        when(store.resolve(anyString())).thenReturn(Mono.just(resolved));
        when(store.countCall(anyLong())).thenReturn(Mono.just(2));
        return store;
    }

    @Test
    @DisplayName("a self-made key stops working once the paid feature is off for its owner")
    void selfMadeKeyNeedsTheFeature() {
        ApiKeyStore store = storeResolving(key("hr@fieldline.test", "HR_MANAGER", List.of(ApiKeyScopes.JOBS_READ), true));
        assertNull(run(store, new AiAccessPolicy("")));
        verify(store, never()).countCall(anyLong());
        assertTrue(ApiKeyReachFilter.isApiKey(run(store, new AiAccessPolicy("hr@fieldline.test"))));
    }

    @Test
    @DisplayName("an admin's grant is not tied to the paid feature")
    void adminGrantIsABypass() {
        ApiKeyStore store = storeResolving(key("hr@fieldline.test", "HR_MANAGER", List.of(ApiKeyScopes.JOBS_READ), false));
        assertTrue(ApiKeyReachFilter.isApiKey(run(store, new AiAccessPolicy(""))));
    }

    @Test
    @DisplayName("a key is marked as a key, and holds only what its role allows today")
    void principalIsMarkedAndCut() {
        ApiKeyStore store = storeResolving(key("e@fieldline.test", "EMPLOYEE",
                List.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_WRITE), false));
        Authentication auth = run(store, new AiAccessPolicy(""));
        List<String> authorities = auth.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        assertTrue(authorities.contains(ApiKeyScopes.CREDENTIAL_AUTHORITY));
        assertTrue(authorities.contains("SCOPE_jobs:read"));
        assertTrue(!authorities.contains("SCOPE_pipeline:write"), authorities.toString());
    }
}
