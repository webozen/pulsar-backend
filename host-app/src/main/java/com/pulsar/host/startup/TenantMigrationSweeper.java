package com.pulsar.host.startup;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleRegistry;
import com.pulsar.kernel.tenant.MigrationRunner;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TenantMigrationSweeper implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TenantMigrationSweeper.class);

    private final TenantRepository repo;
    private final ModuleRegistry modules;
    private final MigrationRunner migrations;

    public TenantMigrationSweeper(TenantRepository repo, ModuleRegistry modules, MigrationRunner migrations) {
        this.repo = repo;
        this.modules = modules;
        this.migrations = migrations;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (TenantRecord t : repo.findAll()) {
            for (String modId : t.activeModules()) {
                ModuleDefinition def = modules.get(modId);
                if (def == null) continue;
                try {
                    migrations.migrateModule(t.dbName(), def);
                } catch (Exception e) {
                    log.warn("Failed to migrate module {} for tenant {}: {}", modId, t.slug(), e.toString());
                }
            }
        }
    }
}
