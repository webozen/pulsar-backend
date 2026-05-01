package com.pulsar.opendentalcalendar;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OpendentalCalendarModule implements ModuleDefinition {
    @Override public String id() { return "opendental-calendar"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "opendental-calendar",
            "OpenDental Calendar",
            "Full operatory calendar view of today's scheduled appointments.",
            "📅",
            "dental"
        );
    }

    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs);
        // Treat opendental_ai_config as an acceptable credential source so tenants
        // that already set up OpenDental AI don't need a separate onboarding step.
        for (String query : new String[]{
            "SELECT COUNT(*) FROM opendental_calendar_config WHERE id = 1",
            "SELECT COUNT(*) FROM opendental_ai_config WHERE id = 1",
        }) {
            try {
                Integer n = jdbc.queryForObject(query, Integer.class);
                if (n != null && n > 0) return true;
            } catch (Exception ignored) {
                // table may not exist if the other module was never activated
            }
        }
        return false;
    }
}
