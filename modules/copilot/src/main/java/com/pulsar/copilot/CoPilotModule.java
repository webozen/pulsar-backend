package com.pulsar.copilot;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Live agent-assist module. Onboarding is shared with opendental-ai (uses
 * the same Gemini key + OpenDental credentials for tool calls). No separate
 * config table.
 */
// internal — owned by suite, not a standalone registrant
public class CoPilotModule implements ModuleDefinition {
    @Override public String id() { return "copilot"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "copilot",
            "Call Co-Pilot",
            "Gemini listens to live calls and surfaces fact cards + suggested actions to staff. Silent — never speaks.",
            "🤝",
            "dental"
        );
    }
    @Override public boolean isOnboarded(DataSource tenantDs) {
        try {
            var rows = new JdbcTemplate(tenantDs)
                .queryForList("SELECT 1 FROM opendental_ai_config WHERE id = 1");
            return !rows.isEmpty();
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }
}
