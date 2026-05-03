package com.pulsar.ainotes;

import com.pulsar.kernel.credentials.PlaudKeyResolver;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Read-only onboarding status for the AI-Notes wizard. As of Phase 2 of
 * credential centralization, the Plaud bearer token lives in
 * {@code tenant_credentials} and is set/cleared via the admin tenant detail
 * page or the tenant Settings page. This controller now just reports
 * whether the tenant is connected.
 */
@RestController
@RequestMapping("/api/ai-notes/onboarding")
@RequireModule("ai-notes")
public class AiNotesOnboardingController {

    private final PlaudKeyResolver plaudKeyResolver;

    public AiNotesOnboardingController(PlaudKeyResolver plaudKeyResolver) {
        this.plaudKeyResolver = plaudKeyResolver;
    }

    @GetMapping
    public Map<String, Object> status() {
        var t = TenantContext.require();
        boolean connected = plaudKeyResolver.statusForDb(t.dbName()).hasToken();
        return Map.of("connected", connected);
    }
}
