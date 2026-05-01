package com.pulsar.host.api.admin;

import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.translate.GeminiKeyResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-tenant API-key management for the admin UI. Currently only Gemini, but
 * structured per-provider so additional providers (OpenAI, Anthropic, etc.)
 * can be added without endpoint churn. The actual key value is never returned
 * — the UI only sees presence flags and the "use platform default" toggle.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}/api-keys")
public class AdminApiKeysController {

    private final TenantRepository tenantRepo;
    private final GeminiKeyResolver keyResolver;

    public AdminApiKeysController(TenantRepository tenantRepo, GeminiKeyResolver keyResolver) {
        this.tenantRepo = tenantRepo;
        this.keyResolver = keyResolver;
    }

    public record GeminiKeyRequest(String apiKey, Boolean useDefault) {}

    @GetMapping
    public Map<String, Object> get(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        GeminiKeyResolver.TenantKeyStatus status = keyResolver.statusForDb(tenant.dbName());

        Map<String, Object> gemini = new LinkedHashMap<>();
        gemini.put("hasTenantKey", status.hasTenantKey());
        gemini.put("useDefault", status.useDefault());
        gemini.put("defaultAvailable", keyResolver.platformDefaultAvailable());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("providers", Map.of("gemini", gemini));
        return resp;
    }

    @PutMapping("/gemini")
    public Map<String, Object> updateGemini(@PathVariable long id, @RequestBody GeminiKeyRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        // Trim — admins copy/paste keys with trailing whitespace constantly.
        String apiKey = req.apiKey() == null ? null : req.apiKey().trim();
        keyResolver.updateForDb(tenant.dbName(), apiKey, req.useDefault());
        return get(id);
    }

    private TenantRecord resolveTenant(long id) {
        return tenantRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant_not_found"));
    }
}
