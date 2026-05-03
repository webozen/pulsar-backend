package com.pulsar.host.api.tenant;

import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tenant-self-serve credentials endpoint. Mirrors the surface of
 * {@link com.pulsar.host.api.admin.AdminApiKeysController} but scopes
 * to the caller's own tenant via the JWT and {@link TenantContext}.
 *
 * <p>The actual key value is never returned — only presence flags. Tenants
 * can rotate or clear their own provider keys here without going through the
 * super-admin UI; super-admin retains the cross-tenant view via {@code /api/admin/...}.
 */
@RestController
@RequestMapping("/api/tenant/credentials")
public class TenantCredentialsController {

    private final GeminiKeyResolver keyResolver;

    public TenantCredentialsController(GeminiKeyResolver keyResolver) {
        this.keyResolver = keyResolver;
    }

    public record GeminiKeyRequest(String apiKey, Boolean useDefault) {}

    @GetMapping
    public Map<String, Object> get() {
        var t = TenantContext.require();
        GeminiKeyResolver.TenantKeyStatus status = keyResolver.statusForDb(t.dbName());

        Map<String, Object> gemini = new LinkedHashMap<>();
        gemini.put("hasTenantKey", status.hasTenantKey());
        gemini.put("useDefault", status.useDefault());
        gemini.put("defaultAvailable", keyResolver.platformDefaultAvailable());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("providers", Map.of("gemini", gemini));
        return resp;
    }

    @PutMapping("/gemini")
    public Map<String, Object> updateGemini(@RequestBody GeminiKeyRequest req) {
        var t = TenantContext.require();
        Principal p = PrincipalContext.get();
        if (!(p instanceof Principal.TenantUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_user_required");
        }
        Principal.TenantUser tu = (Principal.TenantUser) p;
        // Sanity check — JWT slug must match TenantContext slug; the admin
        // gateway sets both, but we double-up to be defensive against any
        // future code path that diverges.
        if (!tu.slug().equals(t.slug())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_mismatch");
        }
        String apiKey = req.apiKey() == null ? null : req.apiKey().trim();
        keyResolver.updateForDb(t.dbName(), apiKey, req.useDefault(), tu.email(), tu.role());
        return get();
    }
}
