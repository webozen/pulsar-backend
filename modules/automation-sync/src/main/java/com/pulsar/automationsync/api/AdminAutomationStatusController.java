package com.pulsar.automationsync.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.automationsync.client.AutomationPlatformClient;
import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only views and actions on the automation-sync state for one tenant.
 * Path prefix matches the existing admin tenants controller so the UI keeps
 * one consistent /api/admin/tenants/{id}/... convention.
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class AdminAutomationStatusController {

    private final TenantRepository repo;
    private final AutomationPlatformClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminAutomationStatusController(TenantRepository repo, AutomationPlatformClient client) {
        this.repo = repo;
        this.client = client;
    }

    /** Local copy of host-app's AdminGuard.requireAdmin so this module
     *  doesn't have a dependency on host-app. */
    private static void requireAdmin() {
        if (!(PrincipalContext.get() instanceof Principal.Admin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_required");
        }
    }

    @GetMapping("/{id}/automation")
    public ResponseEntity<?> status(@PathVariable long id) {
        requireAdmin();
        TenantRecord t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var r = client.status(t.slug());
        Map<String, Object> out = new HashMap<>();
        out.put("slug", t.slug());
        out.put("syncOk", r.ok());
        out.put("syncStatus", r.status());
        if (!r.ok()) {
            out.put("error", r.error());
            return ResponseEntity.ok(out);
        }
        try {
            JsonNode body = mapper.readTree(r.body());
            out.put("platform", body);
        } catch (Exception e) {
            out.put("platform", r.body());
        }
        return ResponseEntity.ok(out);
    }

    /** Force a full re-provision (idempotent) for cases where sync got out of sync. */
    @PostMapping("/{id}/automation/resync")
    public ResponseEntity<?> resync(@PathVariable long id) {
        requireAdmin();
        TenantRecord t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var r = client.provision(t.slug(), t.name(), t.contactEmail(), t.activeModules());
        return ResponseEntity.ok(Map.of(
            "slug", t.slug(),
            "ok", r.ok(),
            "status", r.status(),
            "error", r.error() == null ? "" : r.error()
        ));
    }
}
