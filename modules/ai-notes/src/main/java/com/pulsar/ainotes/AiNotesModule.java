package com.pulsar.ainotes;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AiNotesModule implements ModuleDefinition {
    @Override public String id() { return "ai-notes"; }

    @Override public ModuleManifest manifest() {
        return new ModuleManifest("ai-notes", "AI Notes", "Recordings, transcripts & summaries", "🎙️", "productivity");
    }

    /**
     * Onboarded once a Plaud bearer token is configured in
     * {@code tenant_credentials}. As of Phase 2 the token moved out of
     * {@code ai_notes_config.plaud_token} into the centralized store.
     */
    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        try {
            Integer n = new JdbcTemplate(tenantDs).queryForObject(
                "SELECT COUNT(*) FROM tenant_credentials "
                    + "WHERE provider = 'plaud' AND key_name = 'bearer_token' "
                    + "AND LENGTH(value_ciphertext) > 0",
                Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
