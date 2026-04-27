package com.pulsar.textsupport;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TextSupportModule implements ModuleDefinition {

    @Override public String id() { return "text-support"; }

    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest(
            "text-support",
            "Text Support",
            "AI-powered SMS inbox: thread intelligence, action items, and Gemini reply suggestions.",
            "💬",
            "operations"
        );
    }

    @Override
    public List<String> migrationLocations() {
        // text-copilot has no tables of its own; it writes via TextThreadStore (text-intel)
        return List.of("classpath:db/migration/text-intel");
    }

    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        try {
            var rows = new JdbcTemplate(tenantDs).queryForList(
                "SELECT 1 FROM voice_provider_config " +
                "WHERE provider_id = 'ringcentral' AND oauth_access_token IS NOT NULL"
            );
            return !rows.isEmpty();
        } catch (DataAccessException e) {
            return false;
        }
    }
}
