package com.pulsar.host.api.admin;

import com.pulsar.kernel.platform.PlatformSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Super-admin platform-level settings management. Distinct from per-tenant
 * credentials at {@code /api/admin/tenants/{id}/api-keys} — these are
 * cross-tenant infrastructure values (AnythingLLM URL/key today).
 *
 * <p>Returns presence flags only (never the value). Tracks which settings
 * are set via DB vs. env-fallback so the UI can hint at where the active
 * value comes from.
 */
@RestController
@RequestMapping("/api/admin/platform-settings")
public class AdminPlatformSettingsController {

    private final PlatformSettingsService settings;
    private final String anythingLlmUrlEnvFallback;
    private final String anythingLlmApiKeyEnvFallback;

    public AdminPlatformSettingsController(
        PlatformSettingsService settings,
        @Value("${pulsar.anythingllm.url:http://localhost:3001}") String anythingLlmUrlEnvFallback,
        @Value("${pulsar.anythingllm.api-key:}") String anythingLlmApiKeyEnvFallback
    ) {
        this.settings = settings;
        this.anythingLlmUrlEnvFallback = anythingLlmUrlEnvFallback;
        this.anythingLlmApiKeyEnvFallback = anythingLlmApiKeyEnvFallback;
    }

    public record SettingValueRequest(String value) {}

    @GetMapping
    public Map<String, Object> list() {
        AdminGuard.requireAdmin();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("settings", Map.of(
            "anythingllm.url", statusFor("anythingllm.url", anythingLlmUrlEnvFallback),
            "anythingllm.api_key", statusFor("anythingllm.api_key", anythingLlmApiKeyEnvFallback)
        ));
        return resp;
    }

    @PutMapping("/{settingKey}")
    public Map<String, Object> update(@PathVariable String settingKey, @RequestBody SettingValueRequest req) {
        AdminGuard.requireAdmin();
        if (!PlatformSettingsService.KNOWN_KEYS.contains(settingKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown_setting_key: " + settingKey);
        }
        try {
            String value = req.value() == null ? null : req.value().trim();
            if (value == null || value.isBlank()) {
                settings.clear(settingKey, null /* admin has no email */);
            } else {
                settings.set(settingKey, value, null);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return list();
    }

    private Map<String, Object> statusFor(String key, String envFallback) {
        PlatformSettingsService.Status s = settings.status(key, envFallback);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasValue", s.hasValue());
        out.put("envFallbackAvailable", s.envFallbackAvailable());
        // Active source — hints at where the runtime value will resolve from.
        out.put("activeSource",
            s.hasValue() ? "platform_db" :
            s.envFallbackAvailable() ? "env" : "none");
        return out;
    }
}
