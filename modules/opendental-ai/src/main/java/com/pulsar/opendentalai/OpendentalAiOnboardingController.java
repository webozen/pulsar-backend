package com.pulsar.opendentalai;

import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.credentials.OpenDentalKeyResolver;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only onboarding status for the OpenDental AI wizard. As of Phase 2 of
 * credential centralization, all three keys (Gemini, OD DeveloperKey, OD
 * CustomerKey) live in {@code tenant_credentials} and are written via the
 * admin/tenant credentials endpoints. This controller now just reports
 * whether the tenant is ready to chat.
 */
@RestController
@RequestMapping("/api/opendental-ai/config")
@RequireModule("opendental-ai")
public class OpendentalAiOnboardingController {

    private final GeminiKeyResolver geminiKeyResolver;
    private final OpenDentalKeyResolver opendentalKeyResolver;

    public OpendentalAiOnboardingController(
        GeminiKeyResolver geminiKeyResolver,
        OpenDentalKeyResolver opendentalKeyResolver
    ) {
        this.geminiKeyResolver = geminiKeyResolver;
        this.opendentalKeyResolver = opendentalKeyResolver;
    }

    @GetMapping
    public Map<String, Object> status() {
        var t = TenantContext.require();
        boolean geminiReady = isGeminiReady(t.dbName());
        OpenDentalKeyResolver.Status odStatus = opendentalKeyResolver.statusForDb(t.dbName());
        boolean onboarded = geminiReady && odStatus.isComplete();
        return Map.of(
            "onboarded", onboarded,
            "geminiConfigured", geminiReady,
            "opendentalConfigured", odStatus.isComplete()
        );
    }

    private boolean isGeminiReady(String dbName) {
        GeminiKeyResolver.TenantKeyStatus s = geminiKeyResolver.statusForDb(dbName);
        return s.hasTenantKey() || (s.useDefault() && geminiKeyResolver.platformDefaultAvailable());
    }
}
