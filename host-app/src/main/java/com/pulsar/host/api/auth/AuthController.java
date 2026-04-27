package com.pulsar.host.api.auth;

import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.auth.PasscodeAuthService;
import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import jakarta.servlet.http.Cookie;
import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleRegistry;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final PasscodeAuthService auth;
    private final JwtService jwt;
    private final TenantLookupService tenants;
    private final ModuleRegistry modules;
    private final TenantDataSources tenantDs;

    public AuthController(
        PasscodeAuthService auth,
        JwtService jwt,
        TenantLookupService tenants,
        ModuleRegistry modules,
        TenantDataSources tenantDs
    ) {
        this.auth = auth;
        this.jwt = jwt;
        this.tenants = tenants;
        this.modules = modules;
        this.tenantDs = tenantDs;
    }

    public record AdminLoginRequest(@NotBlank String passcode) {}
    public record TenantLoginRequest(@NotBlank String slug, @NotBlank String email, @NotBlank String passcode) {}
    public record TokenResponse(String token) {}

    @PostMapping("/admin/login")
    public ResponseEntity<?> adminLogin(@RequestBody AdminLoginRequest req, HttpServletRequest httpReq) {
        if (!auth.verifyAdmin(req.passcode())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_passcode"));
        }
        String token = jwt.issueAdmin();
        // Mirror tenantLogin: also drop the same-origin cookie so admin UIs served
        // alongside the tenant app can hand off auth without separate token plumbing.
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, buildJwtCookie(token, httpReq).toString())
            .body(new TokenResponse(token));
    }

    @PostMapping("/tenant/login")
    public ResponseEntity<?> tenantLogin(@RequestBody TenantLoginRequest req, HttpServletRequest httpReq) {
        // All three of {slug, email, passcode} must match the stored row. Email is
        // verified in PasscodeAuthService alongside the passcode so there's no enumeration
        // window for valid-email / wrong-passcode vs. invalid-email / any-passcode.
        Optional<TenantRecord> t = auth.verifyTenant(req.slug(), req.email(), req.passcode());
        if (t.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
        String token = jwt.issueTenant(req.slug(), req.email());

        // Also drop a same-origin HttpOnly cookie so sibling apps served under the
        // same origin (e.g. Next.js automation at /automation/*) can authenticate
        // without a cross-origin handoff. The JSON body still returns {token} so
        // the Pulsar frontend keeps using its existing Authorization-header flow.
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, buildJwtCookie(token, httpReq).toString())
            .body(new TokenResponse(token));
    }

    /**
     * Refresh an existing (valid or very-recently-expired) JWT. Accepts the token from either the
     * Authorization: Bearer header or the pulsar_jwt cookie, validates the signature, allows up to
     * a 5-minute grace window past expiry, and issues a new token with fresh iat/exp preserving
     * role/slug/email/sub. No DB writes, no user lookup.
     */
    /**
     * Discard the caller's session cookie. We can't invalidate the bearer token
     * itself (stateless JWT), but clearing the cookie stops same-origin apps
     * (e.g. the /automation Next service) from sending it on future requests,
     * which is sufficient for browser-based sign-out. The token's TTL still
     * caps any residual exposure if the client kept a copy.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpReq) {
        ResponseCookie cookie = ResponseCookie.from("pulsar_jwt", "")
            .httpOnly(true)
            .path("/")
            .sameSite("Lax")
            .secure(isHttps(httpReq))
            .maxAge(0)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(Map.of("status", "ok"));
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest httpReq) {
        String token = extractToken(httpReq);
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing_token"));
        }
        String newToken;
        try {
            newToken = jwt.refresh(token, 300L);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_token"));
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, buildJwtCookie(newToken, httpReq).toString())
            .body(new TokenResponse(newToken));
    }

    private static String extractToken(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String candidate = auth.substring(7).trim();
            if (!candidate.isEmpty()) return candidate;
        }
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pulsar_jwt".equals(c.getName())) {
                    String v = c.getValue();
                    if (v != null && !v.isBlank()) return v;
                }
            }
        }
        return null;
    }

    private ResponseCookie buildJwtCookie(String token, HttpServletRequest req) {
        return ResponseCookie.from("pulsar_jwt", token)
            .httpOnly(true)
            .path("/")
            .sameSite("Lax")
            .secure(isHttps(req))
            .maxAge(Duration.ofSeconds(jwt.getTtlSeconds()))
            .build();
    }

    /**
     * Detect HTTPS, honoring reverse proxies. In dev (plain HTTP) we intentionally
     * do NOT set the Secure flag so the cookie works on http://localhost:5173.
     */
    private static boolean isHttps(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-Proto");
        if (fwd != null && !fwd.isBlank()) {
            return "https".equalsIgnoreCase(fwd.split(",")[0].trim());
        }
        return "https".equalsIgnoreCase(req.getScheme());
    }

    @GetMapping("/auth/me")
    public ResponseEntity<?> me() {
        Principal p = PrincipalContext.get();
        if (p == null) return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        if (p instanceof Principal.Admin) {
            return ResponseEntity.ok(Map.of("role", "super_admin"));
        }
        Principal.TenantUser u = (Principal.TenantUser) p;
        TenantInfo t = TenantContext.get();
        Optional<TenantRecord> rec = tenants.bySlug(u.slug());
        Set<String> active = t != null
            ? t.activeModules()
            : rec.map(TenantRecord::activeModules).orElse(Set.of());
        boolean suspended = t != null ? t.suspended() : rec.map(TenantRecord::suspended).orElse(false);
        String dbName = rec.map(TenantRecord::dbName).orElse(null);

        Map<String, Boolean> onboarding = new HashMap<>();
        if (dbName != null && !active.isEmpty()) {
            DataSource ds = tenantDs.forDb(dbName);
            for (String id : active) {
                if (!modules.exists(id)) continue;
                ModuleDefinition def = modules.get(id);
                try {
                    onboarding.put(id, def.isOnboarded(ds));
                } catch (Exception e) {
                    onboarding.put(id, false);
                }
            }
        }

        return ResponseEntity.ok(Map.of(
            "role", "tenant_user",
            "slug", u.slug(),
            "email", u.email(),
            "activeModules", active,
            "suspended", suspended,
            "onboarding", onboarding
        ));
    }
}
