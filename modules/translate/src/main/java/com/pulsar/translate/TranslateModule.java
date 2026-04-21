package com.pulsar.translate;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class TranslateModule implements ModuleDefinition {
    @Override public String id() { return "translate"; }

    @Override public ModuleManifest manifest() {
        return new ModuleManifest("translate", "Translate", "Real-time AI translation", "🌐", "operations");
    }

    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        Integer n = new JdbcTemplate(tenantDs)
            .queryForObject("SELECT COUNT(*) FROM translate_config WHERE id = 1", Integer.class);
        return n != null && n > 0;
    }
}
