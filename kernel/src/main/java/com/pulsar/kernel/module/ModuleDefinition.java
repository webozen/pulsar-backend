package com.pulsar.kernel.module;

import java.util.List;
import javax.sql.DataSource;

public interface ModuleDefinition {
    String id();
    ModuleManifest manifest();

    /** Flyway locations (classpath:...) containing this module's per-tenant migrations. */
    default List<String> migrationLocations() {
        return List.of("classpath:db/migration/" + id());
    }

    /**
     * Whether the given tenant has finished this module's onboarding flow.
     * Default: true (no onboarding needed). Modules that ship a wizard override this
     * and typically check a settings table in their own per-tenant schema.
     */
    default boolean isOnboarded(DataSource tenantDs) {
        return true;
    }
}
