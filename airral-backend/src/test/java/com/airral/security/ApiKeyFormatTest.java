package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiKeyFormatTest {

    @Test
    @DisplayName("a generated key is greppable, environment-tagged, and carries its public id")
    void generatedKeyHasTheAdvertisedShape() {
        ApiKeyFormat.Generated key = ApiKeyFormat.generate("live");

        assertTrue(key.raw().startsWith("airral_ak_live_"),
                "prefix is what makes a leaked key findable by a secret scanner");
        assertTrue(key.raw().contains("_" + key.keyId() + "_"),
                "the public id must be recoverable from the key itself");
        assertEquals(64, key.hash().length(), "sha256 hex is 64 characters");
    }

    @Test
    @DisplayName("an API key can never be mistaken for a session JWE")
    void neverCollidesWithAJwe() {
        // SecurityContextRepository decides which credential it holds purely by
        // shape. If these two formats could ever overlap, a key would be handed
        // to the JWT manager or the reverse, and the failure would look like a
        // random 401.
        String raw = ApiKeyFormat.generate("live").raw();

        assertEquals(1, raw.split("\\.", -1).length,
                "a key must contain no dots; a JWE is exactly five dot-separated segments");
        assertTrue(ApiKeyFormat.looksLikeApiKey(raw));
        assertFalse(ApiKeyFormat.looksLikeApiKey("aaa.bbb.ccc.ddd.eee"),
                "a JWE must not be claimed by the API key path");
    }

    @Test
    @DisplayName("hashing is stable for the same key and different for any other")
    void hashingIsStableAndDistinct() {
        ApiKeyFormat.Generated key = ApiKeyFormat.generate("live");

        assertEquals(key.hash(), ApiKeyFormat.sha256(key.raw()),
                "lookup rehashes the presented key, so this must match what was stored");
        assertNotEquals(key.hash(), ApiKeyFormat.sha256(key.raw() + "x"),
                "a near-miss key must not resolve");
    }

    @Test
    @DisplayName("keys and their public ids do not repeat")
    void keysAreUnique() {
        Set<String> raws = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ApiKeyFormat.Generated key = ApiKeyFormat.generate("live");
            raws.add(key.raw());
            ids.add(key.keyId());
        }
        assertEquals(500, raws.size(), "a repeated key would collide on the unique hash index");
        assertEquals(500, ids.size(), "key_id is unique-indexed, so a repeat would fail issuance");
    }

    @Test
    @DisplayName("environment is tagged so a test key cannot read live data unnoticed")
    void environmentIsPartOfTheKey() {
        assertTrue(ApiKeyFormat.generate("test").raw().startsWith("airral_ak_test_"));
        assertTrue(ApiKeyFormat.generate(null).raw().startsWith("airral_ak_live_"),
                "a missing environment defaults to live rather than to something unroutable");
    }

    @Test
    @DisplayName("the display hint identifies a key without helping anyone use it")
    void displayHintRevealsNoSecret() {
        ApiKeyFormat.Generated key = ApiKeyFormat.generate("live");
        String hint = ApiKeyFormat.displayHint("live", key.keyId());

        assertTrue(hint.contains(key.keyId()));
        assertFalse(key.raw().equals(hint));
        assertTrue(hint.endsWith("_..."), "the secret half is elided, not truncated to something guessable");
    }

    /**
     * Kept in step with KEY_PATTERN in logback-spring.xml by hand. This test is
     * what catches the drift: change the key format without changing the log
     * filter and the assertion below fails rather than keys quietly reaching
     * Cloud Logging.
     */
    private static final Pattern LOG_REDACTION = Pattern.compile("airral_ak_[A-Za-z0-9_]{8,}");

    @Test
    @DisplayName("the log redaction pattern masks a whole key, leaving no usable fragment")
    void logRedactionCoversTheEntireKey() {
        ApiKeyFormat.Generated key = ApiKeyFormat.generate("live");
        String line = "inbound request with Authorization: Bearer " + key.raw() + " rejected";

        String redacted = LOG_REDACTION.matcher(line).replaceAll("airral_ak_[REDACTED]");

        assertFalse(redacted.contains(key.raw()), "the full key must not survive");
        assertFalse(redacted.contains(key.keyId()),
                "even the public id goes, since it is inside the matched run");
        assertTrue(redacted.contains("airral_ak_[REDACTED]"));
        assertTrue(redacted.startsWith("inbound request"), "surrounding context is preserved");
        assertTrue(redacted.endsWith("rejected"));
    }
}
