package com.pulsar.kernel.tenant;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleRegistry;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

@Service
public class MigrationRunner {
    private final TenantDataSources tenantDs;
    private final ModuleRegistry modules;

    public MigrationRunner(TenantDataSources tenantDs, ModuleRegistry modules) {
        this.tenantDs = tenantDs;
        this.modules = modules;
    }

    /** Baseline: create schema + run migrations for currently-active modules. */
    public void migrateTenant(String dbName, Set<String> activeModuleIds) {
        migrateKernel(dbName);
        for (String id : activeModuleIds) {
            if (!modules.exists(id)) continue;
            migrateModule(dbName, modules.get(id));
        }
    }

    /**
     * Run kernel-level migrations on a tenant DB. These are always applied,
     * independent of which modules are activated. Hosts the cross-cutting
     * tables — {@code tenant_credentials} today; future kernel-owned tables
     * land here too.
     */
    public void migrateKernel(String dbName) {
        runFlyway(dbName, "classpath:db/migration/kernel", "flyway_schema_history_kernel");
    }

    /**
     * Run Flyway for a module's migrations.
     *
     * <p>Single-location modules use the historical history-table name
     * {@code flyway_schema_history_<module-id>} so existing tenants don't
     * see a forced re-baseline.
     *
     * <p>Multi-location modules (e.g. {@code call-handling} which aggregates
     * {@code caller-match}, {@code call-intel}, {@code copilot}) get one
     * Flyway invocation per location and a per-location history table
     * {@code flyway_schema_history_<module-id>__<location-slug>}. This
     * sidesteps Flyway's "multiple migrations with version 1" error when
     * each sub-suite independently starts at V1.
     */
    public void migrateModule(String dbName, ModuleDefinition module) {
        List<String> locations = module.migrationLocations();
        if (locations.isEmpty()) return;
        if (locations.size() == 1) {
            runFlyway(dbName, locations.get(0), "flyway_schema_history_" + module.id());
            return;
        }
        for (String location : locations) {
            String subId = lastPathSegment(location);
            runFlyway(dbName, location, "flyway_schema_history_" + module.id() + "__" + subId);
        }
    }

    private void runFlyway(String dbName, String location, String historyTable) {
        Flyway flyway = Flyway.configure()
            .dataSource(tenantDs.forDb(dbName))
            .locations(location)
            .table(historyTable)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load();
        flyway.migrate();
    }

    /**
     * Extract the last path segment of a Flyway location URI for use as a
     * history-table suffix. {@code classpath:db/migration/caller-match} →
     * {@code caller-match}.
     */
    private static String lastPathSegment(String location) {
        String s = location;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
