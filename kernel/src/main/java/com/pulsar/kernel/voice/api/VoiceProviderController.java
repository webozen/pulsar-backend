package com.pulsar.kernel.voice.api;

import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.voice.VoiceProvider;
import com.pulsar.kernel.voice.VoiceProviderRegistry;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single entry point for the frontend to discover which voice provider this
 * tenant is wired to. Powers the {@code useVoiceProvider} hook in
 * {@code @pulsar/ui-kernel/voice}.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceProviderController {

    private final VoiceProviderRegistry registry;
    private final TenantDataSources tenantDs;

    public VoiceProviderController(VoiceProviderRegistry registry, TenantDataSources tenantDs) {
        this.registry = registry;
        this.tenantDs = tenantDs;
    }

    @GetMapping("/active-provider")
    public ResponseEntity<Map<String, Object>> activeProvider() {
        try {
            VoiceProvider p = registry.forCurrentTenant(tenantDs);
            var t = com.pulsar.kernel.tenant.TenantContext.require();
            javax.sql.DataSource ds = tenantDs.forDb(t.dbName());
            return ResponseEntity.ok(Map.of(
                "providerId", p.id(),
                "label", p.label(),
                "embedUrl", p.embedUrl(ds),
                "supportsLiveMedia", p.supportsLiveMedia(ds)
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(424).body(Map.of(
                "error", "no_voice_provider_configured",
                "installed", registry.all().stream().map(VoiceProvider::id).toList()
            ));
        }
    }
}
