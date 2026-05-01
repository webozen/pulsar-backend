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
        Integer n = new JdbcTemplate(tenantDs)
            .queryForObject("SELECT COUNT(*) FROM opendental_calendar_config WHERE id = 1", Integer.class);
        return n != null && n > 0;
    }
}
