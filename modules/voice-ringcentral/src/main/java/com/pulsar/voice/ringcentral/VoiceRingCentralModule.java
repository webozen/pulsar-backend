package com.pulsar.voice.ringcentral;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Marker for the RingCentral provider. {@link #isOnboarded} only flips true
 * once the tenant has saved at least the OAuth token via the onboarding
 * controller — without that, frontend voice modules show the "Configure
 * RingCentral" wizard rather than an empty softphone.
 */
@Component
public class VoiceRingCentralModule implements ModuleDefinition {
    @Override public String id() { return "voice-ringcentral"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest(
            "voice-ringcentral",
            "RingCentral",
            "Connect a RingCentral account so other voice modules (Caller Match, Call Intel, Co-Pilot) light up.",
            "📲",
            "voice"
        );
    }
    @Override public boolean isOnboarded(DataSource tenantDs) {
        var rows = new JdbcTemplate(tenantDs).queryForList(
            "SELECT 1 FROM voice_provider_config WHERE provider_id = 'ringcentral' " +
            "AND (oauth_access_token IS NOT NULL OR embed_url IS NOT NULL)"
        );
        return !rows.isEmpty();
    }
}
