package com.pulsar.callermatch;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Caller-ID screen-pop for dental practices. Onboarding is shared with
 * opendental-ai — if that module has saved its credentials, this one inherits
 * them automatically.
 */
// internal — owned by suite, not a standalone registrant
public class CallerMatchModule implements ModuleDefinition {
    @Override public String id() { return "caller-match"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "caller-match",
            "Caller Match",
            "Screen-pop the patient chart on every inbound call. No AI, just fast.",
            "📞",
            "dental"
        );
    }
    @Override public boolean isOnboarded(DataSource tenantDs) {
        var rows = new JdbcTemplate(tenantDs)
            .queryForList("SELECT 1 FROM opendental_ai_config WHERE id = 1");
        return !rows.isEmpty();
    }
}
