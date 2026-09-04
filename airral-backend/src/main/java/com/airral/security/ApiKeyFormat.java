package com.airral.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The shape of an API key, and the only place it is hashed.
 *
 * <pre>
 *   airral_ak_live_7Fq2xK9d_4tPzR8nWvBmY3sLqE6hJcXaGuNfD2kT
 *   |            |    |        |
 *   |            |    |        `-- 256 bits of secret. Never stored.
 *   |            |    `----------- public key id. Stored in clear, safe in logs.
 *   |            `---------------- environment
 *   `----------------------------- fixed prefix, so a leaked key is greppable
 * </pre>
 *
 * The prefix earns its length: secret scanners match on it, and so can you when
 * looking for a key in a log you did not mean to write.
 *
 * It also cannot be mistaken for a JWE. {@link SecurityContextRepository}
 * decides which credential it is holding by shape, and a JWE is exactly five
 * dot-separated segments while a key has none -- so the two can never collide,
 * whatever either format grows into later.
 */
public final class ApiKeyFormat {

    public static final String PREFIX = "airral_ak_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Base62. No look-alike-free trimming: these are copy-pasted, not read aloud. */
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final int KEY_ID_LENGTH = 8;
    private static final int SECRET_LENGTH = 43;   // 43 base62 chars > 256 bits

    private ApiKeyFormat() {
    }

    /** A newly minted key. The raw value exists only in this object. */
    public record Generated(String raw, String keyId, String hash) {
    }

    public static Generated generate(String environment) {
        String env = (environment == null || environment.isBlank()) ? "live" : environment;
        String keyId = randomString(KEY_ID_LENGTH);
        String secret = randomString(SECRET_LENGTH);
        String raw = PREFIX + env + "_" + keyId + "_" + secret;
        return new Generated(raw, keyId, sha256(raw));
    }

    public static boolean looksLikeApiKey(String candidate) {
        return candidate != null && candidate.startsWith(PREFIX);
    }

    /**
     * sha256 hex of the whole key.
     *
     * <p>Deliberately not a password hash. bcrypt's work factor defends
     * low-entropy human-chosen secrets; the secret here is 256 bits of CSPRNG
     * output, so there is nothing for an attacker to guess. Using bcrypt would
     * add roughly 100ms of CPU to every authenticated request, and an agent
     * working through a task makes dozens.
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing the process is
            // not one we should keep serving requests from.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * What may be shown once a key has been issued: enough to recognise which
     * key this is, and nothing that helps anyone use it.
     */
    public static String displayHint(String environment, String keyId) {
        return PREFIX + environment + "_" + keyId + "_...";
    }

    private static String randomString(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return out.toString();
    }
}
