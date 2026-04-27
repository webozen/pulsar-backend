package com.pulsar.textintel;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// internal — owned by suite, not a standalone registrant
public class TextIntelModule implements ModuleDefinition {
    @Override public String id() { return "text-intel"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "text-intel",
            "Text Intel",
            "Gemini summarizes every SMS conversation. Surfaces action items + intent automatically.",
            "💬",
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
