package com.pulsar.scheduling;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class SchedulingModule implements ModuleDefinition {
    @Override public String id() { return "scheduling"; }

    @Override public ModuleManifest manifest() {
        return new ModuleManifest("scheduling", "Office", "Staff directory & team management", "🏢", "operations");
    }
}
