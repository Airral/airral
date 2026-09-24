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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks a Firebase ID token, and nothing else.
 *
 * <p>Firebase is not AIRRAL's account system. Accounts, passwords, roles and
 * sessions all stay in AIRRAL's database. Firebase is used for one thing AIRRAL
 * cannot do on its own: email a one-time link to an address and confirm that the
 * link was followed. When it was, the browser holds an ID token Google has signed
 * saying so, and this is what reads that token.
 *
 * <p>An ID token is accepted only when every one of these holds:
 * <ul>
 *   <li>it is an RS256 JWT whose signature verifies against the key Google
 *       publishes for Firebase under the token's own key id;</li>
 *   <li>{@code iss} and {@code aud} name <em>this</em> Firebase project -- a
 *       token minted for somebody else's project proves nothing here;</li>
 *   <li>it has not expired, was not issued in the future, and the sign-in it
 *       records ({@code auth_time}) happened within {@link #MAX_AUTH_AGE} -- so a
 *       token lifted from an old session cannot be replayed to reset a
 *       password;</li>
 *   <li>{@code email_verified} is true and an email is present.</li>
 * </ul>
 *
 * <p>Written alongside {@link GoogleIdentityService} rather than sharing its
 * code, deliberately: that path is live and signs people in, and this change
 * should not be able to break it. They differ in one real way besides issuer and
 * audience -- Firebase rotates its signing keys, so an unknown key id here
 * triggers one refetch before the token is refused, instead of failing until the
 * cache expires.
 */
@Service
public class FirebaseIdentityService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    static final String DEFAULT_JWK_URL =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    /** How recently the person must have followed the link for the token to count. */
    static final Duration MAX_AUTH_AGE = Duration.ofMinutes(15);

    /** Tolerated clock difference between Google and this server. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    private static final Duration JWK_CACHE_TTL = Duration.ofMinutes(30);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String projectId;
    private final String jwkUrl;
    private final Clock clock;
    private final AtomicReference<CachedJwks> cachedJwks = new AtomicReference<>();

    // @Autowired because there are two constructors: Spring refuses to guess
    // between them and fails the whole context at start-up, which no unit test
    // sees because they construct the class directly.
    @org.springframework.beans.factory.annotation.Autowired
    public FirebaseIdentityService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${airral.auth.firebase.project-id:}") String projectId,
            @Value("${airral.auth.firebase.jwk-url:" + DEFAULT_JWK_URL + "}") String jwkUrl) {
        this(webClientBuilder, objectMapper, projectId, jwkUrl, Clock.systemUTC());
    }

    FirebaseIdentityService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            String projectId,
            String jwkUrl,
            Clock clock) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.projectId = projectId == null ? "" : projectId.trim();
        this.jwkUrl = jwkUrl;
        this.clock = clock;
    }

    /** The verified facts an ID token carries, once it has passed every check. */
    public record VerifiedEmail(String email, String firebaseUid, Instant authTime) {}

    public Mono<VerifiedEmail> verifyIdToken(String idToken) {
        if (!StringUtils.hasText(projectId)) {
            return Mono.error(new BadRequestException("Email verification is not configured"));
        }
        if (!StringUtils.hasText(idToken)) {
            return Mono.error(new BadRequestException("A verification token is required"));
        }
        return Mono.fromCallable(() -> parse(idToken))
                .flatMap(parts -> keyFor(parts.keyId())
                        .switchIfEmpty(Mono.error(new BadRequestException("Verification token was not signed by Firebase")))
                        .flatMap(key -> Mono.fromCallable(() -> {
                            if (!signatureVerifies(parts, key)) {
                                throw new BadRequestException("Verification token signature is invalid");
                            }
                            return checkClaims(parts.payload());
                        })));
    }

    private TokenParts parse(String token) {
        String[] segments = token.trim().split("\\.");
        if (segments.length != 3) {
            throw new BadRequestException("Verification token is malformed");
        }
        Map<String, Object> header;
        Map<String, Object> payload;
        byte[] signature;
        try {
            header = objectMapper.readValue(decode(segments[0]), JSON_OBJECT);
            payload = objectMapper.readValue(decode(segments[1]), JSON_OBJECT);
            signature = decode(segments[2]);
        } catch (IllegalArgumentException | IOException ex) {
            throw new BadRequestException("Verification token is malformed");
        }
        if (header == null || payload == null) {
            throw new BadRequestException("Verification token is malformed");
        }
        // RS256 only. Accepting whatever alg the header names is the classic
        // way to be handed an unsigned ("none") or HMAC-with-the-public-key token.
        String alg = string(header.get("alg"));
        String kid = string(header.get("kid"));
        if (!"RS256".equals(alg) || !StringUtils.hasText(kid)) {
            throw new BadRequestException("Verification token header is invalid");
        }
        return new TokenParts(segments[0] + "." + segments[1], signature, kid, payload);
    }

    VerifiedEmail checkClaims(Map<String, Object> payload) {
        Instant now = clock.instant();
        String expectedIssuer = "https://securetoken.google.com/" + projectId;

        if (!expectedIssuer.equals(string(payload.get("iss")))) {
            throw new BadRequestException("Verification token was issued for a different project");
        }
        if (!projectId.equals(string(payload.get("aud")))) {
            throw new BadRequestException("Verification token was issued for a different project");
        }
        Instant expiresAt = Instant.ofEpochSecond(number(payload.get("exp")));
        if (!expiresAt.isAfter(now.minus(CLOCK_SKEW))) {
            throw new BadRequestException("Verification token has expired. Open the link from your email again.");
        }
        Instant issuedAt = Instant.ofEpochSecond(number(payload.get("iat")));
        if (issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new BadRequestException("Verification token is not valid yet");
        }
        Instant authTime = Instant.ofEpochSecond(number(payload.get("auth_time")));
        if (authTime.isAfter(now.plus(CLOCK_SKEW)) || authTime.isBefore(now.minus(MAX_AUTH_AGE))) {
            throw new BadRequestException("This link was used too long ago. Request a new one.");
        }
        String uid = string(payload.get("sub"));
        if (!StringUtils.hasText(uid)) {
            throw new BadRequestException("Verification token is missing its subject");
        }
        String email = string(payload.get("email")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!StringUtils.hasText(email)) {
            throw new BadRequestException("Verification token is missing an email address");
        }
        if (!bool(payload.get("email_verified"))) {
            throw new BadRequestException("That email address has not been confirmed");
        }
        return new VerifiedEmail(email, uid, authTime);
    }

    /** The public key for a key id, refetching once if the id is not in the cache. */
    private Mono<RSAPublicKey> keyFor(String keyId) {
        return loadJwks(false)
                .flatMap(keys -> find(keys, keyId).map(Mono::just)
                        .orElseGet(() -> loadJwks(true).flatMap(fresh -> Mono.justOrEmpty(find(fresh, keyId)))))
                .flatMap(jwk -> Mono.fromCallable(() -> rsaKey(jwk)));
    }

    private Optional<Map<String, Object>> find(List<Map<String, Object>> keys, String keyId) {
        return keys.stream().filter(k -> keyId.equals(string(k.get("kid")))).findFirst();
    }

    private Mono<List<Map<String, Object>>> loadJwks(boolean force) {
        CachedJwks current = cachedJwks.get();
        if (!force && current != null && current.expiresAt().isAfter(clock.instant())) {
            return Mono.just(current.keys());
        }
        return webClient.get()
                .uri(jwkUrl)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    Object keys = response.get("keys");
                    if (!(keys instanceof List<?> raw)) {
                        throw new BadRequestException("Firebase signing keys could not be read");
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> list = (List<Map<String, Object>>) (List<?>) raw;
                    cachedJwks.set(new CachedJwks(list, clock.instant().plus(JWK_CACHE_TTL)));
                    return list;
                });
    }

    private RSAPublicKey rsaKey(Map<String, Object> jwk) throws Exception {
        String n = string(jwk.get("n"));
        String e = string(jwk.get("e"));
        if (!StringUtils.hasText(n) || !StringUtils.hasText(e)) {
            throw new BadRequestException("Firebase signing key is invalid");
        }
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, decode(n)), new BigInteger(1, decode(e))));
    }

    private boolean signatureVerifies(TokenParts parts, RSAPublicKey key) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(parts.signingInput().getBytes(StandardCharsets.UTF_8));
        try {
            return verifier.verify(parts.signature());
        } catch (SignatureException ex) {
            // A malformed signature is a token that is not accepted -- a 400,
            // not a 500.
            return false;
        }
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(string(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(string(value));
    }

    private record TokenParts(String signingInput, byte[] signature, String keyId, Map<String, Object> payload) {}

    private record CachedJwks(List<Map<String, Object>> keys, Instant expiresAt) {}
}
