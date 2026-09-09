package com.airral.service;

import com.airral.exception.BadRequestException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GoogleIdentityService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final long JWK_CACHE_TTL_SECONDS = 1800;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String jwkUrl;
    private final AtomicReference<CachedJwks> cachedJwks = new AtomicReference<>();

    public GoogleIdentityService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${airral.auth.google.client-id:}") String clientId,
            @Value("${airral.auth.google.jwk-url:https://www.googleapis.com/oauth2/v3/certs}") String jwkUrl) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.jwkUrl = jwkUrl;
    }

    public Mono<GoogleProfile> verifyCredential(String credential) {
        if (!StringUtils.hasText(clientId)) {
            return Mono.error(new BadRequestException("Google sign-in is not configured"));
        }
        if (!StringUtils.hasText(credential)) {
            return Mono.error(new BadRequestException("Google credential is required"));
        }

        return Mono.fromCallable(() -> parseTokenParts(credential))
                .flatMap(parts -> loadJwks().flatMap(jwks -> verifyWithJwks(parts, jwks)));
    }

    /**
     * Splits a credential into the three parts a signature check needs.
     *
     * <p>Every failure in here is a 400, because every one of them is something
     * the caller sent. That was not true while the decode and the parse ran
     * uncaught, and it started to matter the moment POST /api/auth/google got a
     * mapping and became a reachable public route:
     *
     * <ul>
     *   <li>"aaaa.aaaa.aaaa" decodes cleanly and is not JSON, so Jackson threw
     *       JsonParseException. That is an IOException, not a RuntimeException,
     *       so it went straight past GlobalExceptionHandler's RuntimeException
     *       handler to the Exception catch-all and answered 500 -- carrying the
     *       parser's dump of the offending bytes out with it.
     *   <li>The literal JSON {@code null} ("bnVsbA") parses to a null Map, and
     *       header.get("alg") on it threw NullPointerException. Also a 500.
     * </ul>
     *
     * <p>Both are trivially reachable by anything posting junk at the endpoint,
     * and both minted 5xx on a route where the caller was simply wrong.
     */
    private TokenParts parseTokenParts(String credential) {
        String[] segments = credential.split("\\.");
        if (segments.length != 3) {
            throw new BadRequestException("Invalid Google credential");
        }

        Map<String, Object> header;
        Map<String, Object> payload;
        byte[] signature;
        try {
            header = objectMapper.readValue(decodeBase64Url(segments[0]), JSON_OBJECT);
            payload = objectMapper.readValue(decodeBase64Url(segments[1]), JSON_OBJECT);
            signature = decodeBase64Url(segments[2]);
        } catch (IllegalArgumentException | IOException ex) {
            // Deliberately not echoing the parser's message: it quotes the input
            // back, and the input is somebody's attempt at a credential.
            throw new BadRequestException("Invalid Google credential");
        }

        if (header == null || payload == null) {
            throw new BadRequestException("Invalid Google credential");
        }

        String algorithm = asString(header.get("alg"));
        String keyId = asString(header.get("kid"));
        if (!"RS256".equals(algorithm) || !StringUtils.hasText(keyId)) {
            throw new BadRequestException("Invalid Google credential header");
        }

        return new TokenParts(segments[0] + "." + segments[1], signature, keyId, payload);
    }

    private Mono<List<Map<String, Object>>> loadJwks() {
        CachedJwks current = cachedJwks.get();
        if (current != null && current.expiresAt().isAfter(Instant.now())) {
            return Mono.just(current.keys());
        }

        return webClient.get()
                .uri(jwkUrl)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    Object keys = response.get("keys");
                    if (!(keys instanceof List<?> rawKeys)) {
                        throw new BadRequestException("Google keys response was invalid");
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> normalizedKeys = (List<Map<String, Object>>) (List<?>) rawKeys;
                    cachedJwks.set(new CachedJwks(normalizedKeys, Instant.now().plusSeconds(JWK_CACHE_TTL_SECONDS)));
                    return normalizedKeys;
                });
    }

    private Mono<GoogleProfile> verifyWithJwks(TokenParts parts, List<Map<String, Object>> jwks) {
        return Mono.fromCallable(() -> {
            Map<String, Object> jwk = jwks.stream()
                    .filter(key -> parts.keyId().equals(asString(key.get("kid"))))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("Google signing key was not found"));

            RSAPublicKey publicKey = buildRsaPublicKey(jwk);
            if (!verifiedSignature(parts, publicKey)) {
                throw new BadRequestException("Invalid Google credential signature");
            }

            return buildVerifiedProfile(parts.payload());
        });
    }

    private RSAPublicKey buildRsaPublicKey(Map<String, Object> jwk) throws Exception {
        String modulusValue = asString(jwk.get("n"));
        String exponentValue = asString(jwk.get("e"));
        if (!StringUtils.hasText(modulusValue) || !StringUtils.hasText(exponentValue)) {
            throw new BadRequestException("Google signing key was invalid");
        }

        BigInteger modulus = new BigInteger(1, decodeBase64Url(modulusValue));
        BigInteger exponent = new BigInteger(1, decodeBase64Url(exponentValue));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    /**
     * Whether the caller's signature checks out against Google's key.
     *
     * <p>Signature.verify answers false for a wrong signature but <em>throws</em>
     * for a malformed one -- wrong length, wrong encoding -- and
     * SignatureException is checked, so it escaped Mono.fromCallable as itself
     * and answered 500 rather than 400. A signature that cannot be checked is a
     * credential that is not accepted, which is the same answer either way.
     */
    private boolean verifiedSignature(TokenParts parts, RSAPublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(parts.signingInput().getBytes(StandardCharsets.UTF_8));
        try {
            return verifier.verify(parts.signature());
        } catch (SignatureException ex) {
            return false;
        }
    }

    private GoogleProfile buildVerifiedProfile(Map<String, Object> payload) {
        String issuer = asString(payload.get("iss"));
        if (!"accounts.google.com".equals(issuer) && !"https://accounts.google.com".equals(issuer)) {
            throw new BadRequestException("Google credential issuer is invalid");
        }

        String audience = asString(payload.get("aud"));
        if (!clientId.equals(audience)) {
            throw new BadRequestException("Google credential audience is invalid");
        }

        long expiresAt = asLong(payload.get("exp"));
        if (expiresAt <= Instant.now().getEpochSecond()) {
            throw new BadRequestException("Google credential expired");
        }

        String email = asString(payload.get("email"));
        if (!StringUtils.hasText(email)) {
            throw new BadRequestException("Google credential is missing email");
        }

        boolean emailVerified = asBoolean(payload.get("email_verified"));
        if (!emailVerified) {
            throw new BadRequestException("Google email is not verified");
        }

        String subject = asString(payload.get("sub"));
        if (!StringUtils.hasText(subject)) {
            throw new BadRequestException("Google credential is missing subject");
        }

        return new GoogleProfile(
                subject,
                email.trim().toLowerCase(),
                asString(payload.get("given_name")),
                asString(payload.get("family_name")),
                asString(payload.get("name")),
                asString(payload.get("picture"))
        );
    }

    private byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(asString(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(asString(value));
    }

    private record TokenParts(String signingInput, byte[] signature, String keyId, Map<String, Object> payload) {}

    private record CachedJwks(List<Map<String, Object>> keys, Instant expiresAt) {}

    public record GoogleProfile(
            String subject,
            String email,
            String firstName,
            String lastName,
            String fullName,
            String pictureUrl
    ) {}
}
