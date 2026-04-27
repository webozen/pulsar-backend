package com.pulsar.textcopilot;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// internal — owned by suite, not a standalone registrant
public class TextCopilotModule implements ModuleDefinition {
    @Override public String id() { return "text-copilot"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "text-copilot",
            "Text Co-Pilot",
            "Gemini suggests SMS reply phrasings based on the patient's conversation history.",
            "✨",
            "voice"
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
