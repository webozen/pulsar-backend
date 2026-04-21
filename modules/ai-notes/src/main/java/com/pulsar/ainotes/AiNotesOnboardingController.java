package com.pulsar.ainotes;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai-notes/onboarding")
@RequireModule("ai-notes")
public class AiNotesOnboardingController {

    private final TenantDataSources tenantDs;

    public AiNotesOnboardingController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record TokenRequest(@NotBlank String plaudToken) {}

    @GetMapping
    public Map<String, Object> status() {
        JdbcTemplate jdbc = jdbc();
        return jdbc.queryForList(
            "SELECT connected_at FROM ai_notes_config WHERE id = 1"
        ).stream().findFirst()
            .map(row -> Map.of("connected", (Object) true, "connectedAt", row.get("connected_at").toString()))
            .orElse(Map.of("connected", false));
    }

    @PostMapping
    public Map<String, Object> connect(@RequestBody TokenRequest req) {
        jdbc().update(
            "INSERT INTO ai_notes_config (id, plaud_token) VALUES (1, ?) " +
            "ON DUPLICATE KEY UPDATE plaud_token = VALUES(plaud_token), connected_at = NOW()",
            req.plaudToken()
        );
        return Map.of("connected", true);
    }

    @DeleteMapping
    public Map<String, Object> disconnect() {
        jdbc().update("DELETE FROM ai_notes_config WHERE id = 1");
        return Map.of("connected", false);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
