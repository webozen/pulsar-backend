package com.pulsar.opendentalai;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class OpendentalAiModule implements ModuleDefinition {
    @Override public String id() { return "opendental-ai"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "opendental-ai",
            "OpenDental AI",
            "Ask questions about your practice. Powered by Gemini + the OpenDental Query API.",
            "💬",
            "dental"
        );
    }
}
