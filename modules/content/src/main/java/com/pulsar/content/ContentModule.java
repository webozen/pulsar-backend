package com.pulsar.content;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class ContentModule implements ModuleDefinition {
    @Override public String id() { return "content"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("content", "Content", "Posts, pages, and media", "📝", "marketing");
    }
}
