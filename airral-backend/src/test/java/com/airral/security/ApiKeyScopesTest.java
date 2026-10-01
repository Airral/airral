package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiKeyScopesTest {

    @Test
    @DisplayName("an applicant key can never hold an employer scope")
    void applicantCannotEscalateToEmployerScopes() {
        // This is the property the whole scope model exists to guarantee. If a
        // requested scope list were trusted, a flaw in the issuing screen would
        // mint a key that reads other people's hiring pipelines.
        List<String> granted = ApiKeyScopes.grantable("APPLICANT",
                List.of(ApiKeyScopes.PIPELINE_READ,
                        ApiKeyScopes.PIPELINE_WRITE,
                        ApiKeyScopes.ADMIN_KEYS,
                        ApiKeyScopes.JOBS_READ));

        assertEquals(List.of(ApiKeyScopes.JOBS_READ), granted,
                "only the scope the role actually permits survives");
        assertFalse(granted.contains(ApiKeyScopes.PIPELINE_READ));
        assertFalse(granted.contains(ApiKeyScopes.ADMIN_KEYS));
    }

    @Test
    @DisplayName("an employer key cannot mint keys")
    void employerCannotIssueKeys() {
        assertFalse(ApiKeyScopes.maximumFor("HR_MANAGER").contains(ApiKeyScopes.ADMIN_KEYS),
                "key issuance stays with admins, or an employer could widen their own reach");
    }

    @Test
    @DisplayName("no role can source candidates, because that scope does not exist yet")
    void sourcingIsNotGrantableByAnyRole() {
        // Searching candidates who never applied to you is a separate product
        // with a consent model attached. It must not be reachable by adding a
        // string to a request.
        for (String role : List.of("APPLICANT", "HR_MANAGER", "MANAGER", "EMPLOYEE", "ADMIN")) {
            assertTrue(ApiKeyScopes.grantable(role, List.of("sourcing:read")).isEmpty(),
                    role + " must not be able to request sourcing");
        }
    }

    @Test
    @DisplayName("an unrecognised role gets nothing rather than a default")
    void unknownRoleGetsNoScopes() {
        // A key that authenticates but reaches no endpoint is a visible bug.
        // One that quietly inherits applicant scope is not.
        assertTrue(ApiKeyScopes.maximumFor("SOMETHING_NEW").isEmpty());
        assertTrue(ApiKeyScopes.maximumFor(null).isEmpty());
        assertTrue(ApiKeyScopes.grantable("SOMETHING_NEW", List.of(ApiKeyScopes.JOBS_READ)).isEmpty());
    }

    @Test
    @DisplayName("asking for nothing grants the role's full set")
    void emptyRequestMeansRoleDefault() {
        assertEquals(ApiKeyScopes.maximumFor("APPLICANT").size(),
                ApiKeyScopes.grantable("APPLICANT", List.of()).size());
        assertEquals(ApiKeyScopes.maximumFor("APPLICANT").size(),
                ApiKeyScopes.grantable("APPLICANT", null).size());
    }

    @Test
    @DisplayName("an unknown scope is dropped, not refused")
    void unknownScopesAreIgnored() {
        // A client pinned to an older scope name should keep working with the
        // scopes that do still exist, rather than failing issuance outright.
        List<String> granted = ApiKeyScopes.grantable("APPLICANT",
                List.of(ApiKeyScopes.JOBS_READ, "jobs:teleport"));

        assertEquals(List.of(ApiKeyScopes.JOBS_READ), granted);
    }

    @Test
    @DisplayName("role is matched case-insensitively")
    void roleCaseDoesNotMatter() {
        assertEquals(ApiKeyScopes.maximumFor("APPLICANT"), ApiKeyScopes.maximumFor("applicant"));
    }

    @Test
    @DisplayName("scope authorities carry the prefix Spring expects")
    void authoritiesArePrefixed() {
        assertEquals("SCOPE_jobs:read", ApiKeyScopes.authority(ApiKeyScopes.JOBS_READ));
    }

    @Test
    @DisplayName("each employer role's ceiling matches what it can do in the HR portal")
    void employerCeilingsFollowTheirRole() {
        assertTrue(ApiKeyScopes.maximumFor("HR_MANAGER").contains(ApiKeyScopes.JOBS_WRITE));
        assertEquals(java.util.Set.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_READ),
                ApiKeyScopes.maximumFor("MANAGER"));
        assertEquals(java.util.Set.of(ApiKeyScopes.JOBS_READ), ApiKeyScopes.maximumFor("EMPLOYEE"));
    }

    @Test
    @DisplayName("a key someone makes for themselves is read-only, and admins make none")
    void selfServiceIsReadOnly() {
        assertEquals(List.of(ApiKeyScopes.JOBS_READ), ApiKeyScopes.selfService("APPLICANT"));
        assertEquals(List.of(ApiKeyScopes.JOBS_READ), ApiKeyScopes.selfService("EMPLOYEE"));
        assertEquals(List.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_READ),
                ApiKeyScopes.selfService("HR_MANAGER"));
        assertEquals(List.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_READ),
                ApiKeyScopes.selfService("MANAGER"));
        assertTrue(ApiKeyScopes.selfService("ADMIN").isEmpty());
        assertTrue(ApiKeyScopes.selfService(null).isEmpty());
        for (String role : List.of("APPLICANT", "HR_MANAGER", "MANAGER", "EMPLOYEE")) {
            assertTrue(ApiKeyScopes.maximumFor(role).containsAll(ApiKeyScopes.selfService(role)), role);
            assertFalse(ApiKeyScopes.selfService(role).contains(ApiKeyScopes.PIPELINE_WRITE), role);
            assertFalse(ApiKeyScopes.selfService(role).contains(ApiKeyScopes.JOBS_WRITE), role);
        }
    }

    @Test
    @DisplayName("a key issued before a ceiling narrowed is cut to the ceiling it has now")
    void effectiveScopesFollowTodaysCeiling() {
        // An employee key issued when every employer role had the full set.
        List<String> held = List.of(ApiKeyScopes.JOBS_READ, ApiKeyScopes.PIPELINE_WRITE, ApiKeyScopes.JOBS_WRITE);
        assertEquals(List.of(ApiKeyScopes.JOBS_READ), ApiKeyScopes.effective("EMPLOYEE", held));
        assertTrue(ApiKeyScopes.effective("SOMETHING_NEW", held).isEmpty());
        assertTrue(ApiKeyScopes.effective("APPLICANT", null).isEmpty());
    }
}
