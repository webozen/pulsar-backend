package com.pulsar.kernel.module;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ModuleRegistry {
    private final Map<String, ModuleDefinition> byId;

    public ModuleRegistry(List<ModuleDefinition> modules) {
        this.byId = modules.stream().collect(Collectors.toUnmodifiableMap(ModuleDefinition::id, m -> m));
    }

    public List<ModuleDefinition> all() {
        return List.copyOf(byId.values());
    }

    public ModuleDefinition get(String id) {
        ModuleDefinition m = byId.get(id);
        if (m == null) throw new IllegalArgumentException("Unknown module: " + id);
        return m;
    }

    public boolean exists(String id) {
        return byId.containsKey(id);
    }
}
