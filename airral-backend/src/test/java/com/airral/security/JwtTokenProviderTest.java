package com.airral.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    private static final String STRONG_SECRET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_!@#";

    @Test
    void generatesAndValidatesHs512TokenWithRequiredClaims() {
        JwtTokenProvider provider = createProvider(new String[]{"test"}, STRONG_SECRET);

        String token = provider.generateToken(
                42L,
                "candidate@airral.test",
                "APPLICANT",
                null,
                null,
                false,
                null,
                null
        );

        assertEquals(5, token.split("\\.", -1).length);
        assertFalse(token.contains("candidate@airral.test"));
        assertTrue(provider.validateToken(token));
        assertEquals("candidate@airral.test", provider.getEmailFromToken(token));
        assertEquals(42L, provider.getUserIdFromToken(token));
        assertEquals("APPLICANT", provider.getRoleFromToken(token));
        assertEquals("airral-api", provider.getAllClaimsFromToken(token).getIssuer());
        assertTrue(provider.getAllClaimsFromToken(token).getAudience().contains("airral-web"));
    }

    @Test
    void rejectsDefaultDevelopmentSecretOutsideLocalProfiles() {
        JwtTokenProvider provider = createProvider(
                new String[]{},
                "mySuperLongDefaultJwtSecretKeyForDevelopmentOnly_ChangeMe_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                false
        );

        assertThrows(IllegalStateException.class, provider::validateJwtConfiguration);
    }

    @Test
    void rejectsUnsignedNoneAlgorithmToken() {
        JwtTokenProvider provider = createProvider(new String[]{"test"}, STRONG_SECRET);
        String unsignedToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0."
                + "eyJzdWIiOiJjYW5kaWRhdGVAYWlycmFsLnRlc3QiLCJleHAiOjQxMDI0NDQ4MDB9.";

        assertFalse(provider.validateToken(unsignedToken));
    }

    private JwtTokenProvider createProvider(String[] activeProfiles, String secret) {
        return createProvider(activeProfiles, secret, true);
    }

    private JwtTokenProvider createProvider(String[] activeProfiles, String secret, boolean validate) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);

        JwtTokenProvider provider = new JwtTokenProvider(environment, new ObjectMapper());
        ReflectionTestUtils.setField(provider, "jwtEncryptionSecret", secret);
        ReflectionTestUtils.setField(provider, "jwtExpiration", 900000L);
        ReflectionTestUtils.setField(provider, "jwtIssuer", "airral-api");
        ReflectionTestUtils.setField(provider, "jwtAudience", "airral-web");
        ReflectionTestUtils.setField(provider, "jwtClockSkewSeconds", 30L);

        if (validate) {
            provider.validateJwtConfiguration();
        }

        return provider;
    }
}
