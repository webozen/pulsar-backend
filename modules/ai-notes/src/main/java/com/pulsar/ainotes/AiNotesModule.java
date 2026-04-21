package com.pulsar.ainotes;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class AiNotesModule implements ModuleDefinition {
    @Override public String id() { return "ai-notes"; }

    @Override public ModuleManifest manifest() {
        return new ModuleManifest("ai-notes", "AI Notes", "Recordings, transcripts & summaries", "🎙️", "productivity");
    }

    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        Integer n = new JdbcTemplate(tenantDs)
            .queryForObject("SELECT COUNT(*) FROM ai_notes_config WHERE id = 1", Integer.class);
        return n != null && n > 0;
    }
}
