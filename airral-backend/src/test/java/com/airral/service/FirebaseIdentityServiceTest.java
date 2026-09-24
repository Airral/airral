package com.airral.service;

import com.airral.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Firebase ID token check, against real RSA signatures and a real HTTP key
 * endpoint -- not a mocked verifier. This is the one piece of code that decides
 * whether someone proved they own an address, so every rule it enforces has a
 * case that breaks exactly that rule and nothing else.
 */
class FirebaseIdentityServiceTest {

    private static final String PROJECT = "airral-test";
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair signingKey;
    private KeyPair otherKey;
    private HttpServer jwksServer;
    private final List<Map<String, Object>> publishedKeys = new ArrayList<>();
    private final AtomicInteger jwksFetches = new AtomicInteger();
    private FirebaseIdentityService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
        publishedKeys.add(jwk("key-1", signingKey));

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            jwksFetches.incrementAndGet();
            byte[] body = mapper.writeValueAsBytes(Map.of("keys", publishedKeys));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();

        service = new FirebaseIdentityService(WebClient.builder(), mapper, PROJECT,
                "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        jwksServer.stop(0);
    }

    @Test
    @DisplayName("a token Firebase signed for this project, for a clicked link, is accepted")
    void acceptsAValidToken() {
        StepVerifier.create(service.verifyIdToken(token("key-1", signingKey, claims())))
                .assertNext(proof -> {
                    assertThat(proof.email()).isEqualTo("bob@example.com");
                    assertThat(proof.firebaseUid()).isEqualTo("firebase-uid-1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the address comes back lowercased, matching how accounts are stored")
    void lowercasesTheAddress() {
        Map<String, Object> claims = claims();
        claims.put("email", "Bob@Example.COM");
        StepVerifier.create(service.verifyIdToken(token("key-1", signingKey, claims)))
                .assertNext(proof -> assertThat(proof.email()).isEqualTo("bob@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a token signed by any other key is refused")
    void refusesAForgedSignature() {
        // Right header, right claims, published key id -- signed by a key that
        // is not the one published under that id.
        assertRefused(token("key-1", otherKey, claims()), "signature");
    }

    @Test
    @DisplayName("a token for somebody else's Firebase project is refused")
    void refusesAnotherProject() {
        Map<String, Object> wrongAudience = claims();
        wrongAudience.put("aud", "someone-elses-project");
        assertRefused(token("key-1", signingKey, wrongAudience), "project");

        Map<String, Object> wrongIssuer = claims();
        wrongIssuer.put("iss", "https://securetoken.google.com/someone-elses-project");
        assertRefused(token("key-1", signingKey, wrongIssuer), "project");
    }

    @Test
    @DisplayName("an expired token is refused")
    void refusesExpired() {
        Map<String, Object> claims = claims();
        claims.put("exp", NOW.minusSeconds(3600).getEpochSecond());
        assertRefused(token("key-1", signingKey, claims), "expired");
    }

    @Test
    @DisplayName("a token from a sign-in long ago cannot be replayed")
    void refusesAnOldSignIn() {
        // Still inside its hour of validity, but the link was followed twenty
        // minutes ago. A reset must come from a link just followed.
        Map<String, Object> claims = claims();
        claims.put("auth_time", NOW.minusSeconds(20 * 60).getEpochSecond());
        assertRefused(token("key-1", signingKey, claims), "too long ago");
    }

    @Test
    @DisplayName("an address Firebase has not confirmed is refused")
    void refusesUnverifiedEmail() {
        Map<String, Object> claims = claims();
        claims.put("email_verified", false);
        assertRefused(token("key-1", signingKey, claims), "not been confirmed");
    }

    @Test
    @DisplayName("an unsigned or HMAC token is refused before any key is consulted")
    void refusesOtherAlgorithms() {
        for (String alg : new String[] {"none", "HS256"}) {
            String header = b64(Map.of("alg", alg, "kid", "key-1", "typ", "JWT"));
            String payload = b64(claims());
            assertRefused(header + "." + payload + ".", "malformed", "header");
            assertRefused(header + "." + payload + ".c2ln", "header");
        }
    }

    @Test
    @DisplayName("a rotated signing key is picked up by refetching once, not refused until the cache expires")
    void refetchesOnUnknownKeyId() {
        StepVerifier.create(service.verifyIdToken(token("key-1", signingKey, claims()))).expectNextCount(1).verifyComplete();
        int fetchesBefore = jwksFetches.get();

        // Firebase rotates: a new key appears that the cached set does not have.
        publishedKeys.add(jwk("key-2", otherKey));
        StepVerifier.create(service.verifyIdToken(token("key-2", otherKey, claims()))).expectNextCount(1).verifyComplete();

        assertThat(jwksFetches.get()).isEqualTo(fetchesBefore + 1);
    }

    @Test
    @DisplayName("a key id that is not published at all is refused")
    void refusesUnknownKeyId() {
        assertRefused(token("key-nobody-published", signingKey, claims()), "not signed by Firebase");
    }

    // --- helpers -----------------------------------------------------------

    private Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://securetoken.google.com/" + PROJECT);
        c.put("aud", PROJECT);
        c.put("sub", "firebase-uid-1");
        c.put("email", "bob@example.com");
        c.put("email_verified", true);
        c.put("iat", NOW.minusSeconds(60).getEpochSecond());
        c.put("auth_time", NOW.minusSeconds(60).getEpochSecond());
        c.put("exp", NOW.plusSeconds(3000).getEpochSecond());
        return c;
    }

    private void assertRefused(String token, String... expectedMessageFragments) {
        StepVerifier.create(service.verifyIdToken(token))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    String message = error.getMessage().toLowerCase();
                    assertThat(java.util.Arrays.stream(expectedMessageFragments).map(String::toLowerCase).anyMatch(message::contains))
                            .as("message '%s' should mention one of %s", error.getMessage(),
                                    java.util.Arrays.toString(expectedMessageFragments))
                            .isTrue();
                })
                .verify();
    }

    private String token(String kid, KeyPair key, Map<String, Object> claims) {
        try {
            String signingInput = b64(Map.of("alg", "RS256", "kid", kid, "typ", "JWT")) + "." + b64(claims);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String b64(Map<String, Object> json) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> jwk(String kid, KeyPair key) {
        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return Map.of("kid", kid, "kty", "RSA", "alg", "RS256", "use", "sig",
                "n", enc.encodeToString(unsigned(pub.getModulus().toByteArray())),
                "e", enc.encodeToString(unsigned(pub.getPublicExponent().toByteArray())));
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
