package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiAccessPolicyTest {

    private static final AiAccessPolicy POLICY =
            new AiAccessPolicy(" Maya@Example.com ; hr@fieldline.test,other@x.test ");

    private static ApiKeyStore.SelfServiceUser applicant(String email, boolean verified) {
        return new ApiKeyStore.SelfServiceUser(1L, email, "APPLICANT", null, true, verified, null, true);
    }

    private static ApiKeyStore.SelfServiceUser employer(String role, String companyStatus, boolean companyActive) {
        return new ApiKeyStore.SelfServiceUser(2L, "hr@fieldline.test", role, 29L, true, true,
                companyStatus, companyActive);
    }

    @Test
    @DisplayName("an account on the list, verified, may make a key")
    void listedVerifiedApplicantIsAllowed() {
        AiAccessPolicy.Decision decision = POLICY.decide(applicant("maya@example.com", true));
        assertTrue(decision.included());
        assertTrue(decision.available());
    }

    @Test
    @DisplayName("the list is matched without regard to case or spacing")
    void listIsForgiving() {
        assertTrue(POLICY.decide(applicant("MAYA@EXAMPLE.COM", true)).available());
        assertTrue(POLICY.decide(employer("HR_MANAGER", "VERIFIED", true)).available());
    }

    @Test
    @DisplayName("an account not on the list does not have the paid feature")
    void unlistedIsNotIncluded() {
        AiAccessPolicy.Decision decision = POLICY.decide(applicant("sam@example.com", true));
        assertFalse(decision.included());
        assertEquals("NOT_INCLUDED", decision.reason());
    }

    @Test
    @DisplayName("an empty list turns the feature off for everyone")
    void emptyListIsOff() {
        assertFalse(new AiAccessPolicy("").decide(applicant("maya@example.com", true)).included());
        assertFalse(new AiAccessPolicy(null).decide(applicant("maya@example.com", true)).included());
    }

    @Test
    @DisplayName("a listed account still has to prove its email first")
    void unverifiedEmailIsBlocked() {
        AiAccessPolicy.Decision decision = POLICY.decide(applicant("maya@example.com", false));
        assertTrue(decision.included());
        assertFalse(decision.available());
        assertEquals("EMAIL_NOT_VERIFIED", decision.reason());
    }

    @Test
    @DisplayName("an employer's company must be approved and active")
    void companyMustBeApproved() {
        assertEquals("COMPANY_NOT_VERIFIED", POLICY.decide(employer("HR_MANAGER", "PENDING", true)).reason());
        assertEquals("COMPANY_NOT_VERIFIED", POLICY.decide(employer("MANAGER", "REJECTED", true)).reason());
        assertEquals("COMPANY_NOT_VERIFIED", POLICY.decide(employer("EMPLOYEE", "VERIFIED", false)).reason());
    }

    @Test
    @DisplayName("admins and unknown roles never self-issue, even when listed")
    void adminsNeverSelfIssue() {
        ApiKeyStore.SelfServiceUser admin = new ApiKeyStore.SelfServiceUser(
                3L, "maya@example.com", "ADMIN", null, true, true, null, true);
        assertEquals("ROLE_NOT_ALLOWED", POLICY.decide(admin).reason());
        assertFalse(POLICY.decide(admin).included());
    }

    @Test
    @DisplayName("an inactive or missing account gets nothing")
    void inactiveGetsNothing() {
        ApiKeyStore.SelfServiceUser inactive = new ApiKeyStore.SelfServiceUser(
                1L, "maya@example.com", "APPLICANT", null, false, true, null, true);
        assertFalse(POLICY.decide(inactive).included());
        assertFalse(POLICY.decide(null).included());
    }
}
