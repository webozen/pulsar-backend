package com.pulsar.host.api.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulsar.host.api.admin.AdminGuard;
import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the domain-level theme (loaded at startup from
 * {@code classpath:branding/<domain>/theme.json}) merged with any per-tenant
 * overrides stored in {@code public_tenants.branding}. Fields absent from the
 * tenant override fall through to the domain default.
 *
 * <ul>
 *   <li>{@code GET /api/tenant/branding} — anyone signed in; returns merged theme</li>
 *   <li>{@code PATCH /api/admin/tenants/{id}/branding} — admin only; set/clear tenant override</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class BrandingController {

    private final String domain;
    private final TenantLookupService tenants;
    private final TenantRepository repo;
    private final ObjectMapper json = new ObjectMapper();

    /** Loaded once at startup — domain branding is immutable for the lifetime of the JVM. */
    private ObjectNode domainTheme;

    public BrandingController(
        @Value("${pulsar.domain}") String domain,
        TenantLookupService tenants,
        TenantRepository repo
    ) {
        this.domain = domain;
        this.tenants = tenants;
        this.repo = repo;
    }

    @PostConstruct
    public void load() throws IOException {
        String path = "branding/" + domain + "/theme.json";
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            // Fall back to generic if the compiled-in domain's theme is missing.
            res = new ClassPathResource("branding/generic/theme.json");
        }
        try (InputStream in = res.getInputStream()) {
            domainTheme = (ObjectNode) json.readTree(in);
            domainTheme.put("domain", domain);
        }
    }

    @GetMapping("/tenant/branding")
    public ResponseEntity<?> current() {
        Principal p = PrincipalContext.get();
        // Start with a fresh copy of the domain theme so we don't mutate the cached one.
        ObjectNode merged = domainTheme.deepCopy();
        merged.put("source", "domain");

        // Authenticated tenant? Apply their override on top.
        if (p instanceof Principal.TenantUser u) {
            Optional<TenantRecord> rec = tenants.bySlug(u.slug());
            if (rec.isPresent() && rec.get().brandingJson() != null && !rec.get().brandingJson().isBlank()) {
                try {
                    JsonNode override = json.readTree(rec.get().brandingJson());
                    if (override.isObject()) {
                        merged.setAll((ObjectNode) override);
                        merged.put("source", "tenant");
                    }
                } catch (IOException e) {
                    // Malformed tenant branding — log and fall back to domain-only.
                    // Don't fail the request; the UI should still render something.
                }
            }
        }
        return ResponseEntity.ok(merged);
    }

    @PatchMapping("/admin/tenants/{id}/branding")
    public ResponseEntity<?> updateBranding(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        AdminGuard.requireAdmin();
        repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body == null || body.isNull()) {
            // Explicit clear — wipe back to pure domain defaults.
            repo.updateBranding(id, null);
        } else {
            if (!body.isObject()) {
                return ResponseEntity.badRequest().body(Map.of("error", "branding must be a JSON object or null"));
            }
            repo.updateBranding(id, body.toString());
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
