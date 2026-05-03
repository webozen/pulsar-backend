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
            "OpenDental",
            "Appointments, patient records, and SMS — powered by OpenDental.",
            "📅",
            "dental"
        );
    }

    /**
     * Onboarded once OpenDental keys (DeveloperKey + CustomerKey) are
     * configured in {@code tenant_credentials}. As of Phase 2 the OD keys
     * moved out of {@code opendental_calendar_config}/{@code opendental_ai_config}
     * into the centralized credential store. We can't easily call the resolver
     * bean from {@link ModuleDefinition#isOnboarded} (gets a DataSource, not a
     * dbName), so we read the canonical store directly via the supplied
     * DataSource — same SQL the resolver uses.
     */
    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        try {
            Integer n = new JdbcTemplate(tenantDs).queryForObject(
                "SELECT COUNT(*) FROM tenant_credentials "
                    + "WHERE provider = 'opendental' "
                    + "AND key_name IN ('developer_key','customer_key') "
                    + "AND LENGTH(value_ciphertext) > 0",
                Integer.class);
            // Both keys must be present.
            return n != null && n >= 2;
        } catch (Exception e) {
            return false;
        }
    }
}
