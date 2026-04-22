package com.pulsar.opendental;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class OpendentalModule implements ModuleDefinition {
    @Override public String id() { return "opendental"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "opendental",
            "OpenDental Sync",
            "Sync patients and appointments from Open Dental.",
            "🦷",
            "dental"
        );
    }
}
