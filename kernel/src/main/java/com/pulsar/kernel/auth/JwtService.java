package com.pulsar.kernel.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String DEV_SENTINEL =
        "dev-secret-change-me-please-32bytes-minimum-abcdefgh";

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(
        @Value("${pulsar.jwt.secret}") String secret,
        @Value("${pulsar.jwt.ttl-seconds}") long ttlSeconds,
        Environment environment
    ) {
        validateSecret(secret, environment);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    private static void validateSecret(String secret, Environment environment) {
        String[] active = environment.getActiveProfiles();
        boolean hasProd = Arrays.stream(active).anyMatch(p -> p.equalsIgnoreCase("prod"));
        boolean hasDevLike = Arrays.stream(active).anyMatch(p -> {
            String lower = p.toLowerCase();
            return lower.startsWith("dev") || lower.startsWith("local") || lower.startsWith("test");
        });
        // prod-like if "prod" is active, or if some non-dev/local/test profile is active.
        // No active profile at all => dev-like, to preserve local DX.
        boolean prodLike = hasProd || (active.length > 0 && !hasDevLike);

        if (prodLike) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                    "PULSAR_JWT_SECRET must be set in prod-like profiles; got null/blank.");
            }
            if (DEV_SENTINEL.equals(secret)) {
                throw new IllegalStateException(
                    "PULSAR_JWT_SECRET is set to the public dev sentinel in a prod-like profile; "
                        + "refusing to start. Set a real secret via the PULSAR_JWT_SECRET env var.");
            }
            if (secret.length() < 32) {
                throw new IllegalStateException(
                    "PULSAR_JWT_SECRET must be at least 32 characters in prod-like profiles; got "
                        + secret.length() + ".");
            }
        } else {
            if (DEV_SENTINEL.equals(secret)) {
                log.warn(
                    "PULSAR_JWT_SECRET is using the public dev sentinel. This is OK for local dev "
                        + "but MUST be overridden in any shared or deployed environment.");
            }
        }
    }

    /** Exposed so controllers can align cookie Max-Age with JWT expiration. */
    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String issueAdmin() {
        return build(Map.of("role", "super_admin"), "admin");
    }

    public String issueTenant(String tenantSlug, String email) {
        return build(Map.of("role", "tenant_user", "slug", tenantSlug, "email", email), email);
    }

    private String build(Map<String, Object> claims, String subject) {
        Instant now = Instant.now();
        return Jwts.builder()
            .claims(claims)
            .subject(subject)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Parse a token for refresh purposes, permitting expired tokens as long as they fall within the
     * given grace window (expiration was at most {@code graceSeconds} ago). Throws on invalid
     * signatures or tokens that are stale beyond the grace window.
     */
    public Claims parseAllowingGrace(String token, long graceSeconds) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            Claims claims = e.getClaims();
            Date exp = claims.getExpiration();
            if (exp == null) {
                throw e;
            }
            long expiredBySeconds = (System.currentTimeMillis() - exp.getTime()) / 1000L;
            if (expiredBySeconds > graceSeconds) {
                throw e;
            }
            return claims;
        }
    }

    /**
     * Parse a possibly-expired token (within the grace window) and immediately mint a new one
     * preserving role/slug/email/sub. Throws if signature is invalid or the token is stale beyond
     * grace. Convenience wrapper around {@link #parseAllowingGrace} + {@link #reissueFromClaims}
     * so HTTP-layer callers don't need to import {@link Claims}.
     */
    public String refresh(String token, long graceSeconds) {
        Claims claims = parseAllowingGrace(token, graceSeconds);
        return reissueFromClaims(claims);
    }

    /**
     * Mint a fresh token copying role/slug/email/sub from the supplied claims. Intended for the
     * /api/auth/refresh flow so we reissue without revalidating credentials or hitting the DB.
     */
    public String reissueFromClaims(Claims original) {
        Map<String, Object> claims = new HashMap<>();
        String role = original.get("role", String.class);
        if (role != null) claims.put("role", role);
        String slug = original.get("slug", String.class);
        if (slug != null) claims.put("slug", slug);
        String email = original.get("email", String.class);
        if (email != null) claims.put("email", email);

        String subject = original.getSubject();
        if (subject == null) {
            // Fall back to role-based default so we never emit a subjectless token.
            subject = "tenant_user".equals(role) ? (email != null ? email : "tenant") : "admin";
        }
        return build(claims, subject);
    }
}
