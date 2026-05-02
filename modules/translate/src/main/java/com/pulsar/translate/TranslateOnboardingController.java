package com.pulsar.translate;

import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only onboarding status for the translate wizard. The Gemini key itself
 * is set/cleared via {@code /api/admin/tenants/{id}/api-keys/gemini} (super-admin)
 * or {@code /api/tenant/credentials/gemini} (tenant-admin) — not here.
 *
 * <p>This controller used to write {@code translate_config.gemini_key}; that
 * column was dropped in the pre-v1 cleanup. The wizard now only queries this
 * endpoint to render "configured / not configured" status.
 */
@RestController
@RequestMapping("/api/translate/onboarding")
@RequireModule("translate")
public class TranslateOnboardingController {

    private final GeminiKeyResolver geminiKeyResolver;

    public TranslateOnboardingController(GeminiKeyResolver geminiKeyResolver) {
        this.geminiKeyResolver = geminiKeyResolver;
    }

    @GetMapping
    public Map<String, Object> status() {
        var t = TenantContext.require();
        GeminiKeyResolver.TenantKeyStatus s = geminiKeyResolver.statusForDb(t.dbName());
        // Wizard only needs the boolean — drop the prior `configuredAt` field
        // (that column tracked the legacy gemini_key write timestamp; the
        // canonical "when was this set" lives in tenant_credentials.updated_at
        // and isn't user-facing today).
        return Map.of("configured", s.hasTenantKey() || s.useDefault());
    }
}
