package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of password reset that do not need a database. Everything that does
 * -- unknown and real addresses answering alike, one use per link, expiry, the
 * old password dying -- is exercised end to end in scripts/verify-local.sh,
 * because a mocked DatabaseClient would only confirm the SQL was called.
 */
class PasswordResetTokenTest {

    @Test
    @DisplayName("tokens carry 256 bits and never repeat")
    void tokensAreLongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = PasswordResetService.newToken();
            // 32 random bytes, URL-safe base64 without padding.
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    @DisplayName("only a hash is stored, and it is the same SHA-256 the gate computes")
    void storedHashIsSha256() {
        // printf 'abc' | shasum -a 256 -- the exact recipe verify-local.sh uses to
        // plant a token, so the gate and the service cannot silently disagree.
        assertThat(PasswordResetService.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String token = PasswordResetService.newToken();
        assertThat(PasswordResetService.sha256(token)).hasSize(64).doesNotContain(token);
    }

    @Test
    @DisplayName("the link carries the token in the fragment, never the query string")
    void tokenTravelsInTheFragment() {
        PasswordResetService service = new PasswordResetService(
                null, null, null, null, null, null, "https://apply.airral.com/reset-password");
        String link = service.resetLink("TOKEN123");
        assertThat(link).isEqualTo("https://apply.airral.com/reset-password#token=TOKEN123");
        assertThat(link).doesNotContain("?");
    }
}
