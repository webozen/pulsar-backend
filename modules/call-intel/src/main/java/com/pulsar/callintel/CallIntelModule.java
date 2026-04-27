package com.pulsar.callintel;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Post-call AI: Gemini transcribes and summarizes recorded calls, then links
 * the summary back to the OpenDental patient. Shares credentials with
 * opendental-ai so there is no separate onboarding wizard.
 */
// internal — owned by suite, not a standalone registrant
public class CallIntelModule implements ModuleDefinition {
    @Override public String id() { return "call-intel"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "call-intel",
            "Call Intel",
            "Gemini reviews every recorded call and writes summaries, action items, and patient tags.",
            "📝",
            "dental"
        );
    }
    @Override public boolean isOnboarded(DataSource tenantDs) {
        var rows = new JdbcTemplate(tenantDs)
            .queryForList("SELECT 1 FROM opendental_ai_config WHERE id = 1");
        return !rows.isEmpty();
    }
}
