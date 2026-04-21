package com.pulsar.crm;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class CrmModule implements ModuleDefinition {
    @Override public String id() { return "crm"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("crm", "CRM", "Contacts, deals, and interactions", "🤝", "sales");
    }
}
