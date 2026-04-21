package com.pulsar.inventory;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class InventoryModule implements ModuleDefinition {
    @Override public String id() { return "inventory"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("inventory", "Inventory", "Stock levels and product catalog", "📦", "operations");
    }
}
