package com.airral.security;

import io.jsonwebtoken.*;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final String DEFAULT_DEV_SECRET =
            "mySuperLongDefaultJwtSecretKeyForDevelopmentOnly_ChangeMe_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int ENCRYPTION_SECRET_MIN_BYTES = 32;

    @Value("${jwt.encryption-secret}")
    private String jwtEncryptionSecret;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Value("${jwt.issuer:airral-api}")
    private String jwtIssuer;

    @Value("${jwt.audience:airral-web}")
    private String jwtAudience;

    @Value("${jwt.clock-skew-seconds:30}")
    private long jwtClockSkewSeconds;

    private final Environment environment;
    private final ObjectMapper objectMapper;

    public JwtTokenProvider(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void validateJwtConfiguration() {
        if (jwtEncryptionSecret == null || jwtEncryptionSecret.isBlank()) {
            throw new IllegalStateException("JWT_ENCRYPTION_SECRET must be configured");
        }

        int keyBytes = jwtEncryptionSecret.getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes < ENCRYPTION_SECRET_MIN_BYTES) {
            throw new IllegalStateException("JWT_ENCRYPTION_SECRET must be at least 32 bytes for A256GCM");
        }

        if (DEFAULT_DEV_SECRET.equals(jwtEncryptionSecret) && !isSafeLocalProfile()) {
            throw new IllegalStateException("The default development JWT encryption secret cannot be used outside local/test profiles");
        }
    }

    /**
     * Generate JWT token with user claims
     */
    public String generateToken(Long userId, String email, String role, Long organizationId, 
                                String organizationTier, Boolean isPlatformAdmin, 
                                String department, Long managerId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);
        claims.put("role", role);
        claims.put("organizationId", organizationId);
        claims.put("organizationTier", organizationTier);
        claims.put("isPlatformAdmin", isPlatformAdmin != null ? isPlatformAdmin : false);
        
        if (department != null) {
            claims.put("department", department);
        }
        if (managerId != null) {
            claims.put("managerId", managerId);
        }

        return Jwts.builder()
                .setClaims(claims)
                .issuer(jwtIssuer)
                .subject(email)
                .audience().add(jwtAudience).and()
                .issuedAt(now)
                .notBefore(now)
                .expiration(expiryDate)
                .id(UUID.randomUUID().toString())
                .encryptWith(getEncryptionKey(), Jwts.ENC.A256GCM)
                .compact();
    }

    /**
     * Get encryption key from secret
     */
    private SecretKey getEncryptionKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(jwtEncryptionSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for JWT encryption key derivation", ex);
        }
    }

    private Claims parseClaims(String token) {
        requireExpectedEncryption(token);
        return Jwts.parser()
                .decryptWith(getEncryptionKey())
                .requireIssuer(jwtIssuer)
                .requireAudience(jwtAudience)
                .clockSkewSeconds(jwtClockSkewSeconds)
                .build()
                .parseEncryptedClaims(token)
                .getPayload();
    }

    private void requireExpectedEncryption(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5) {
            throw new MalformedJwtException("Encrypted JWT must have five compact JWE parts");
        }

        try {
            byte[] decodedHeader = Base64.getUrlDecoder().decode(parts[0]);
            Map<String, Object> header = objectMapper.readValue(
                    decodedHeader,
                    new TypeReference<Map<String, Object>>() {}
            );

            if (!"dir".equals(header.get("alg")) || !"A256GCM".equals(header.get("enc"))) {
                throw new UnsupportedJwtException("JWT must use dir/A256GCM encryption");
            }
        } catch (IllegalArgumentException | IOException ex) {
            throw new MalformedJwtException("Invalid JWT header", ex);
        }
    }

    private boolean isSafeLocalProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("local")
                        || profile.equalsIgnoreCase("dev")
                        || profile.equalsIgnoreCase("test"));
    }

    /**
     * Extract email from JWT token
     */
    public String getEmailFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.getSubject();
    }

    /**
     * Extract user ID from JWT token
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("userId", Long.class);
    }

    /**
     * Extract organization ID from JWT token (for multi-tenancy)
     */
    public Long getOrganizationIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("organizationId", Long.class);
    }

    /**
     * Extract role from JWT token
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("role", String.class);
    }

    /**
     * Extract organization tier from JWT token (for feature gating)
     */
    public String getOrganizationTierFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("organizationTier", String.class);
    }

    /**
     * Check if user is platform admin
     */
    public Boolean isPlatformAdminFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("isPlatformAdmin", Boolean.class);
    }

    /**
     * Validate JWT token
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (MalformedJwtException ex) {
            logger.warn("Invalid JWT token: {}", ex.getMessage());
        } catch (ExpiredJwtException ex) {
            logger.warn("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            logger.warn("Unsupported JWT token: {}", ex.getMessage());
        } catch (IncorrectClaimException ex) {
            logger.warn("JWT required claim mismatch: {}", ex.getMessage());
        } catch (JwtException ex) {
            logger.warn("JWT validation failed: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            logger.warn("Invalid JWT claims: {}", ex.getMessage());
        }
        return false;
    }

    /**
     * Get all claims from token
     */
    public Claims getAllClaimsFromToken(String token) {
        return parseClaims(token);
    }
}
