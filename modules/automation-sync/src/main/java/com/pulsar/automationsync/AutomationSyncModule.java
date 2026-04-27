package com.pulsar.automationsync;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

/**
 * Marker module so the registry knows automation-sync is present. There is
 * no per-tenant onboarding or runtime gating — the sync infrastructure runs
 * platform-wide whenever any tenant lifecycle event fires.
 */
@Component
public class AutomationSyncModule implements ModuleDefinition {
    @Override public String id() { return "automation-sync"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "automation-sync",
            "Automation Sync",
            "Internal: bridges Pulsar tenants to the workflow platform.",
            "🔗",
            "system"
        );
    }
}
