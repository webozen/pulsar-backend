package com.pulsar.host.api.admin;

import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.translate.TranslateSettings;
import com.pulsar.translate.TranslateSettingsService;
import com.pulsar.translate.history.HistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoints for translate per-tenant configuration and history restore.
 * Lives in host-app because /api/admin/* is the platform-admin namespace
 * (translate-module endpoints under /api/translate/* are tenant-scoped).
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}/translate")
public class AdminTranslateController {

    private final TenantRepository tenantRepo;
    private final TranslateSettingsService settingsService;
    private final HistoryService historyService;

    public AdminTranslateController(
        TenantRepository tenantRepo,
        TranslateSettingsService settingsService,
        HistoryService historyService
    ) {
        this.tenantRepo = tenantRepo;
        this.settingsService = settingsService;
        this.historyService = historyService;
    }

    public record SettingsRequest(
        @Min(5) @Max(120) Integer sessionDurationMin,
        @Min(5) @Max(120) Integer extendGrantMin,
        @Min(0) @Max(20) Integer maxExtends,
        Boolean historyEnabled,
        @Min(1) @Max(3650) Integer historyRetentionDays
    ) {}

    @GetMapping("/settings")
    public Map<String, Object> getSettings(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        TranslateSettings effective = settingsService.forDb(tenant.dbName());
        TranslateSettings defaults = settingsService.defaults();
        Map<String, Object> overrides = settingsService.rawOverridesForDb(tenant.dbName());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("effective", Map.of(
            "sessionDurationMin", effective.sessionDurationMin(),
            "extendGrantMin", effective.extendGrantMin(),
            "maxExtends", effective.maxExtends(),
            "historyEnabled", effective.historyEnabled(),
            "historyRetentionDays", effective.historyRetentionDays()
        ));
        resp.put("defaults", Map.of(
            "sessionDurationMin", defaults.sessionDurationMin(),
            "extendGrantMin", defaults.extendGrantMin(),
            "maxExtends", defaults.maxExtends(),
            "historyEnabled", defaults.historyEnabled(),
            "historyRetentionDays", defaults.historyRetentionDays()
        ));
        Map<String, Object> overridesOut = new LinkedHashMap<>();
        overridesOut.put("sessionDurationMin", overrides.get("session_duration_min"));
        overridesOut.put("extendGrantMin", overrides.get("extend_grant_min"));
        overridesOut.put("maxExtends", overrides.get("max_extends"));
        overridesOut.put("historyEnabled", overrides.get("history_enabled"));
        overridesOut.put("historyRetentionDays", overrides.get("history_retention_days"));
        overridesOut.put("settingsUpdatedAt", overrides.get("settings_updated_at"));
        resp.put("overrides", overridesOut);
        resp.put("historyAvailable", historyService.isAvailable());
        return resp;
    }

    @PatchMapping("/settings")
    public Map<String, Object> updateSettings(@PathVariable long id, @RequestBody SettingsRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        settingsService.updateOverridesForDb(
            tenant.dbName(),
            req.sessionDurationMin(),
            req.extendGrantMin(),
            req.maxExtends(),
            req.historyEnabled(),
            req.historyRetentionDays()
        );
        return getSettings(id);
    }

    @PostMapping("/conversations/{convId}/restore")
    public ResponseEntity<Map<String, Object>> restoreConversation(@PathVariable long id, @PathVariable long convId) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        boolean ok = historyService.repo().restore(tenant.dbName(), convId);
        return ok ? ResponseEntity.ok(Map.of("restored", true))
                  : ResponseEntity.notFound().build();
    }

    private TenantRecord resolveTenant(long id) {
        return tenantRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant_not_found"));
    }
}
