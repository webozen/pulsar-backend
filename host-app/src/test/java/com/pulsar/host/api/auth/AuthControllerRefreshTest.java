package com.pulsar.host.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pulsar.kernel.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Exercises POST /api/auth/refresh by instantiating AuthController with a real JwtService and
 * hand-crafted tokens. Covers: fresh token, recently-expired (within grace), stale (past grace),
 * and missing token.
 */
class AuthControllerRefreshTest {

    // 32+ bytes, deterministic — matches the jjwt requirement for HS256.
    private static final String SECRET = "test-secret-refresh-test-32bytes-minimum-1234567890";
    private static final long TTL = 3600L;

    private static JwtService jwtService;
    private static AuthController controller;
    private static SecretKey key;

    @BeforeAll
    static void init() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        jwtService = new JwtService(SECRET, TTL, env);
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        // Instantiate AuthController via its declared constructor. The refresh handler does not
        // touch auth/tenants/modules/tenantDs, so nulls are safe for these code paths.
        Constructor<AuthController> ctor = AuthController.class.getDeclaredConstructors().length > 0
            ? pickConstructor()
            : null;
        assertNotNull(ctor, "AuthController has no declared constructor");
        controller = ctor.newInstance(null, jwtService, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Constructor<AuthController> pickConstructor() {
        for (Constructor<?> c : AuthController.class.getDeclaredConstructors()) {
            if (c.getParameterCount() == 5) {
                return (Constructor<AuthController>) c;
            }
        }
        throw new IllegalStateException("expected AuthController(5-arg) constructor");
    }

    @Test
    void freshValidTenantTokenReturns200WithNewToken() {
        String token = jwtService.issueTenant("acme", "alice@acme.test");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);

        ResponseEntity<?> res = controller.refresh(req);

        assertEquals(200, res.getStatusCode().value());
        String newToken = extractToken(res);
        assertNotEquals(token, newToken, "should be a freshly-minted token");
        Claims parsed = jwtService.parse(newToken);
        assertEquals("tenant_user", parsed.get("role"));
        assertEquals("acme", parsed.get("slug"));
        assertEquals("alice@acme.test", parsed.get("email"));
        assertNotNull(res.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertTrue(res.getHeaders().getFirst(HttpHeaders.SET_COOKIE).startsWith("pulsar_jwt="));
    }

    @Test
    void tokenExpired2MinAgoIsRefreshable() {
        // exp 2 minutes in the past; grace window is 5 minutes.
        String token = buildToken(
            Map.of("role", "tenant_user", "slug", "acme", "email", "bob@acme.test"),
            "bob@acme.test",
            Instant.now().minusSeconds(3600 + 120),
            Instant.now().minusSeconds(120));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);

        ResponseEntity<?> res = controller.refresh(req);

        assertEquals(200, res.getStatusCode().value());
        String newToken = extractToken(res);
        Claims parsed = jwtService.parse(newToken);
        assertEquals("bob@acme.test", parsed.get("email"));
        assertEquals("acme", parsed.get("slug"));
        assertTrue(parsed.getExpiration().toInstant().isAfter(Instant.now()),
            "new token exp must be in the future");
    }

    @Test
    void tokenExpired10MinAgoIsRejected() {
        String token = buildToken(
            Map.of("role", "tenant_user", "slug", "acme", "email", "carol@acme.test"),
            "carol@acme.test",
            Instant.now().minusSeconds(3600 + 600),
            Instant.now().minusSeconds(600));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);

        ResponseEntity<?> res = controller.refresh(req);
        assertEquals(401, res.getStatusCode().value());
    }

    @Test
    void missingTokenReturns401() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<?> res = controller.refresh(req);
        assertEquals(401, res.getStatusCode().value());
    }

    @Test
    void adminTokenRefreshPreservesAdminRole() {
        String adminToken = jwtService.issueAdmin();
        MockHttpServletRequest req = new MockHttpServletRequest();
        // Also try via cookie rather than header to exercise the other extraction branch.
        req.setCookies(new Cookie("pulsar_jwt", adminToken));

        ResponseEntity<?> res = controller.refresh(req);

        assertEquals(200, res.getStatusCode().value());
        Claims parsed = jwtService.parse(extractToken(res));
        assertEquals("super_admin", parsed.get("role"));
    }

    private static String extractToken(ResponseEntity<?> res) {
        Object body = res.getBody();
        assertNotNull(body, "response body must not be null");
        // TokenResponse is a record with a single 'token' accessor; use reflection to stay
        // decoupled from package-private nesting details.
        try {
            Object tok = body.getClass().getMethod("token").invoke(body);
            assertNotNull(tok, "token in body must not be null");
            return tok.toString();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("expected TokenResponse in body; got " + body, e);
        }
    }

    private static String buildToken(
        Map<String, Object> claims, String subject, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
            .claims(claims)
            .subject(subject)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact();
    }
}
