package com.pulsar.kernel.tenant;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleRegistry;
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
        for (String id : activeModuleIds) {
            if (!modules.exists(id)) continue;
            migrateModule(dbName, modules.get(id));
        }
    }

    public void migrateModule(String dbName, ModuleDefinition module) {
        Flyway flyway = Flyway.configure()
            .dataSource(tenantDs.forDb(dbName))
            .locations(module.migrationLocations().toArray(new String[0]))
            .table("flyway_schema_history_" + module.id())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load();
        flyway.migrate();
    }
}
