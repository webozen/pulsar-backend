package com.pulsar.voice.ringcentral.api;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.voice.ringcentral.RingCentralProvider;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-tenant RingCentral configuration: OAuth tokens (today entered by hand
 * after running the OAuth dance externally; full PKCE flow is a separate
 * ticket), optional embed URL override, and the webhook signing secret.
 */
@RestController
@RequestMapping("/api/voice/ringcentral")
@RequireModule("voice-ringcentral")
public class RingCentralOnboardingController {

    private final TenantDataSources tenantDs;

    public RingCentralOnboardingController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record ConfigRequest(
        String oauthAccessToken,
        String oauthRefreshToken,
        String oauthExpiresAtIso,
        String webhookSecret,
        String embedUrl,
        String clickToDialUrl,
        String rcClientId,
        String rcClientSecret
    ) {}

    @GetMapping("/config")
    public Map<String, Object> status() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        var rows = jdbc.queryForList(
            "SELECT embed_url, oauth_access_token IS NOT NULL AS has_token, " +
            "       webhook_secret IS NOT NULL AS has_secret, configured_at, " +
            "       (rc_client_id IS NOT NULL AND rc_client_secret IS NOT NULL) AS has_client_credentials " +
            "FROM voice_provider_config WHERE provider_id = 'ringcentral'"
        );
        if (rows.isEmpty()) {
            return Map.of(
                "configured", false,
                "embedUrl", RingCentralProvider.DEFAULT_EMBED_URL
            );
        }
        var row = rows.get(0);
        Object urlOverride = row.get("embed_url");
        Object hcc = row.get("has_client_credentials");
        boolean hasClientCredentials = hcc instanceof Number n ? n.intValue() != 0
            : Boolean.TRUE.equals(hcc);
        return Map.of(
            "configured", true,
            "hasToken", row.get("has_token"),
            "hasWebhookSecret", row.get("has_secret"),
            "hasClientCredentials", hasClientCredentials,
            "embedUrl", urlOverride == null ? RingCentralProvider.DEFAULT_EMBED_URL : urlOverride,
            "configuredAt", row.get("configured_at")
        );
    }

    @PostMapping("/config")
    public Map<String, Object> save(@Valid @RequestBody ConfigRequest req) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        jdbc.update(
            "INSERT INTO voice_provider_config (provider_id, embed_url, oauth_access_token, " +
            "  oauth_refresh_token, oauth_expires_at, webhook_secret, click_to_dial_url, " +
            "  rc_client_id, rc_client_secret) " +
            "VALUES ('ringcentral', ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE " +
            "  embed_url = COALESCE(VALUES(embed_url), embed_url), " +
            "  oauth_access_token = COALESCE(VALUES(oauth_access_token), oauth_access_token), " +
            "  oauth_refresh_token = COALESCE(VALUES(oauth_refresh_token), oauth_refresh_token), " +
            "  oauth_expires_at = COALESCE(VALUES(oauth_expires_at), oauth_expires_at), " +
            "  webhook_secret = COALESCE(VALUES(webhook_secret), webhook_secret), " +
            "  click_to_dial_url = COALESCE(VALUES(click_to_dial_url), click_to_dial_url), " +
            "  rc_client_id = COALESCE(VALUES(rc_client_id), rc_client_id), " +
            "  rc_client_secret = COALESCE(VALUES(rc_client_secret), rc_client_secret)",
            blankToNull(req.embedUrl()),
            blankToNull(req.oauthAccessToken()),
            blankToNull(req.oauthRefreshToken()),
            blankToNull(req.oauthExpiresAtIso()),
            blankToNull(req.webhookSecret()),
            blankToNull(req.clickToDialUrl()),
            blankToNull(req.rcClientId()),
            blankToNull(req.rcClientSecret())
        );
        return Map.of("configured", true);
    }

    /** Stub OAuth callback. Returns a placeholder JSON so an admin can
     *  manually paste the code into the config form. Full token-exchange
     *  with PKCE + refresh handling is tracked separately. */
    @GetMapping("/oauth/callback")
    public Map<String, Object> oauthCallback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state
    ) {
        return Map.of(
            "received", true,
            "code", code == null ? "" : code,
            "state", state == null ? "" : state,
            "next", "Paste the access_token from the OAuth dance into POST /api/voice/ringcentral/config."
        );
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
