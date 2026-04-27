package com.pulsar.host.startup;

import com.pulsar.kernel.module.ModuleRegistry;
import com.pulsar.kernel.tenant.MigrationRunner;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-time-safe migration: replaces the old per-feature module IDs that
 * predated the suite grouping with their new suite IDs. Runs at every
 * startup but is idempotent — if the tenant already has the suite ID and
 * none of the old IDs, it is a no-op.
 *
 * <p>Mapping:
 * <ul>
 *   <li>Any of {caller-match, call-intel, copilot} → add "call-handling", remove the individual IDs</li>
 *   <li>Any of {text-intel, text-copilot} → add "text-support", remove the individual IDs</li>
 * </ul>
 */
@Component
@Order(1)
@Profile("!test")
public class CommsSuiteMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommsSuiteMigrationRunner.class);

    private static final Map<String, Set<String>> SUITE_MEMBERS = Map.of(
        "call-handling", Set.of("caller-match", "call-intel", "copilot"),
        "text-support",  Set.of("text-intel", "text-copilot")
    );

    private final TenantRepository repo;
    private final ModuleRegistry modules;
    private final MigrationRunner migrations;

    public CommsSuiteMigrationRunner(TenantRepository repo, ModuleRegistry modules, MigrationRunner migrations) {
        this.repo = repo;
        this.modules = modules;
        this.migrations = migrations;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (TenantRecord t : repo.findAll()) {
            Set<String> updated = migrate(t.activeModules());
            if (!updated.equals(t.activeModules())) {
                repo.updateModules(t.id(), updated);
                log.info("Migrated tenant {} modules: {} → {}", t.slug(), t.activeModules(), updated);
                for (String newId : updated) {
                    if (!t.activeModules().contains(newId) && modules.exists(newId)) {
                        try {
                            migrations.migrateModule(t.dbName(), modules.get(newId));
                        } catch (Exception e) {
                            log.warn("Migration failed for {} / {}: {}", t.slug(), newId, e.toString());
                        }
                    }
                }
            }
        }
    }

    /** Pure function — testable without Spring. */
    static Set<String> migrate(Set<String> current) {
        Set<String> result = new HashSet<>(current);
        for (var entry : SUITE_MEMBERS.entrySet()) {
            String suiteId = entry.getKey();
            Set<String> members = entry.getValue();
            boolean hasAny = members.stream().anyMatch(current::contains);
            if (hasAny) {
                result.add(suiteId);
                result.removeAll(members);
            }
        }
        return result;
    }
}
