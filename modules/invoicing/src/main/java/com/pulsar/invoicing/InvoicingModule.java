package com.pulsar.invoicing;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class InvoicingModule implements ModuleDefinition {
    @Override public String id() { return "invoicing"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("invoicing", "Invoicing", "Invoices, estimates, and payments", "📄", "finance");
    }
}
