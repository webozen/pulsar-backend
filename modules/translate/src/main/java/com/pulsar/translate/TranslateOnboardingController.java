package com.pulsar.translate;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/translate/onboarding")
@RequireModule("translate")
public class TranslateOnboardingController {

    private final TenantDataSources tenantDs;

    public TranslateOnboardingController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record KeyRequest(@NotBlank String geminiKey) {}

    @GetMapping
    public Map<String, Object> status() {
        return jdbc().queryForList("SELECT configured_at FROM translate_config WHERE id = 1")
            .stream().findFirst()
            .map(row -> Map.of("configured", (Object) true, "configuredAt", row.get("configured_at").toString()))
            .orElse(Map.of("configured", false));
    }

    @PostMapping
    public Map<String, Object> configure(@RequestBody KeyRequest req) {
        jdbc().update(
            "INSERT INTO translate_config (id, gemini_key) VALUES (1, ?) " +
            "ON DUPLICATE KEY UPDATE gemini_key = VALUES(gemini_key), configured_at = NOW()",
            req.geminiKey()
        );
        return Map.of("configured", true);
    }

    @DeleteMapping
    public Map<String, Object> remove() {
        jdbc().update("DELETE FROM translate_config WHERE id = 1");
        return Map.of("configured", false);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
