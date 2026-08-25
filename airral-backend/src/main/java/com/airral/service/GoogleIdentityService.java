package com.airral.service;

import com.airral.exception.BadRequestException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
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

    private TokenParts parseTokenParts(String credential) throws Exception {
        String[] segments = credential.split("\\.");
        if (segments.length != 3) {
            throw new BadRequestException("Invalid Google credential");
        }

        Map<String, Object> header = objectMapper.readValue(decodeBase64Url(segments[0]), JSON_OBJECT);
        Map<String, Object> payload = objectMapper.readValue(decodeBase64Url(segments[1]), JSON_OBJECT);

        String algorithm = asString(header.get("alg"));
        String keyId = asString(header.get("kid"));
        if (!"RS256".equals(algorithm) || !StringUtils.hasText(keyId)) {
            throw new BadRequestException("Invalid Google credential header");
        }

        return new TokenParts(segments[0] + "." + segments[1], decodeBase64Url(segments[2]), keyId, payload);
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
            if (!verifySignature(parts.signingInput(), parts.signature(), publicKey)) {
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

    private boolean verifySignature(String signingInput, byte[] signatureBytes, RSAPublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signatureBytes);
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
