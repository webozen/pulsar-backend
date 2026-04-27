package com.pulsar.voice.ringcentral;

import com.pulsar.kernel.voice.VoiceProvider;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RingCentralProvider implements VoiceProvider {

    /** Default embeddable build hosted by RingCentral on GitHub Pages.
     *  Tenants can override via voice_provider_config.embed_url to point at
     *  a self-hosted build (recommended for production HIPAA posture). */
    public static final String DEFAULT_EMBED_URL =
        "https://ringcentral.github.io/ringcentral-embeddable/app.html?disconnectInactiveWebphone=true";

    /** Magic URL the frontend interprets as "use the WebPhone SDK in the parent
     *  page" instead of mounting an iframe. The frontend's adapter registry
     *  routes by URL scheme. Keeping this in the embedUrl field (vs a separate
     *  flag column on /api/voice/active-provider) keeps the provider-info shape
     *  identical for every adapter — no special-cased fields. */
    public static final String WEBPHONE_SDK_URL = "ringcentral-webphone:sdk";

    @Override public String id() { return "ringcentral"; }
    @Override public String label() { return "RingCentral"; }

    @Override public String embedUrl(DataSource tenantDs) {
        var rows = new JdbcTemplate(tenantDs).queryForList(
            "SELECT embed_url, use_web_phone_sdk FROM voice_provider_config WHERE provider_id = 'ringcentral'"
        );
        if (rows.isEmpty()) return DEFAULT_EMBED_URL;
        var r = rows.get(0);
        Object sdkFlag = r.get("use_web_phone_sdk");
        boolean useSdk = sdkFlag instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(sdkFlag);
        if (useSdk) return WEBPHONE_SDK_URL;
        String override = (String) r.get("embed_url");
        return override == null || override.isBlank() ? DEFAULT_EMBED_URL : override;
    }

    @Override public boolean supportsLiveMedia(DataSource tenantDs) {
        try {
            var rows = new JdbcTemplate(tenantDs).queryForList(
                "SELECT use_web_phone_sdk FROM voice_provider_config WHERE provider_id = 'ringcentral'"
            );
            if (rows.isEmpty()) return false;
            Object f = rows.get(0).get("use_web_phone_sdk");
            return f instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(f);
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    /** Kept for backward compat with code that hasn't switched to the
     *  tenant-scoped overload yet. Reports false (safe default). */
    @Override public boolean supportsLiveMedia() { return false; }
}
