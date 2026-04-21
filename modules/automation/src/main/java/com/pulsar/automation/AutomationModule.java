package com.pulsar.automation;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class AutomationModule implements ModuleDefinition {
    @Override public String id() { return "automation"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("automation", "Automation", "Rules, workflows, and triggers", "⚡", "automation");
    }
}
