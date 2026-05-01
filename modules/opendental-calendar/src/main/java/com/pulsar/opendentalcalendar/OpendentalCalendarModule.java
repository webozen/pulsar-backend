package com.pulsar.opendentalcalendar;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class OpendentalCalendarModule implements ModuleDefinition {
    @Override public String id() { return "opendental-calendar"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "opendental-calendar",
            "OpenDental Calendar",
            "Full operatory calendar view of today's scheduled appointments.",
            "📅",
            "dental"
        );
    }
}
