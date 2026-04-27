package com.pulsar.voice.ringcentral;

import com.pulsar.kernel.text.TextProvider;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RingCentral SMS provider — same vendor as {@link RingCentralProvider}; we
 * keep them as separate beans so a future tenant can split voice and text
 * across providers without ripping these apart.
 */
@Component
public class RingCentralTextProvider implements TextProvider {

    @Override public String id() { return "ringcentral"; }
    @Override public String label() { return "RingCentral"; }

    @Override
    public String defaultFromPhone(DataSource tenantDs) {
        try {
            var rows = new JdbcTemplate(tenantDs).queryForList(
                "SELECT default_sms_from_phone FROM voice_provider_config WHERE provider_id = 'ringcentral'"
            );
            if (rows.isEmpty()) return null;
            return (String) rows.get(0).get("default_sms_from_phone");
        } catch (org.springframework.dao.DataAccessException e) {
            return null;
        }
    }
}
