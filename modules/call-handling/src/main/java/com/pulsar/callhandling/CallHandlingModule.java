package com.pulsar.callhandling;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CallHandlingModule implements ModuleDefinition {

    @Override public String id() { return "call-handling"; }

    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest(
            "call-handling",
            "Call Handling",
            "Screen-pop, post-call AI summaries, and live Co-Pilot for every patient call.",
            "📞",
            "operations"
        );
    }

    /**
     * Manifest-only meta-module — the migrations live with the sub-suites
     * (caller-match, call-intel, copilot), each of which is its own
     * {@link ModuleDefinition}. Re-declaring the sub-suite locations here
     * would cause cross-module DDL collisions on tenants that have both
     * call-handling and any sub-suite active. Operators who want the
     * call-handling SKU must activate the sub-suite modules alongside it.
     */
    @Override
    public List<String> migrationLocations() {
        return List.of();
    }

    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        try {
            var rows = new JdbcTemplate(tenantDs).queryForList(
                "SELECT 1 FROM opendental_ai_config WHERE id = 1"
            );
            return !rows.isEmpty();
        } catch (DataAccessException e) {
            return false;
        }
    }
}
