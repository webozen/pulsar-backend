package com.pulsar.hr;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class HrModule implements ModuleDefinition {
    @Override public String id() { return "hr"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("hr", "HR", "Employees, onboarding, and documents", "👥", "people");
    }
}
