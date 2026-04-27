package com.pulsar.kernel.text.api;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.text.TextProvider;
import com.pulsar.kernel.text.TextProviderRegistry;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend entry point: which text provider is wired for this tenant?
 * Powers the {@code useTextProvider} hook in {@code @pulsar/ui-kernel}.
 */
@RestController
@RequestMapping("/api/text")
public class TextProviderController {

    private final TextProviderRegistry registry;
    private final TenantDataSources tenantDs;

    public TextProviderController(TextProviderRegistry registry, TenantDataSources tenantDs) {
        this.registry = registry;
        this.tenantDs = tenantDs;
    }

    @GetMapping("/active-provider")
    public ResponseEntity<Map<String, Object>> activeProvider() {
        try {
            TextProvider p = registry.forCurrentTenant(tenantDs);
            var t = TenantContext.require();
            return ResponseEntity.ok(Map.of(
                "providerId", p.id(),
                "label", p.label(),
                "defaultFromPhone", java.util.Objects.requireNonNullElse(
                    p.defaultFromPhone(tenantDs.forDb(t.dbName())), "")
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(424).body(Map.of(
                "error", "no_text_provider_configured",
                "installed", registry.all().stream().map(TextProvider::id).toList()
            ));
        }
    }
}
