package com.pulsar.translate;

import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Resolves which Gemini API key the WS handler should authenticate with for
 * a given tenant. Priority:
 *   1) If translate_config.use_default_gemini_key = true → platform default
 *      from PULSAR_DEFAULT_GEMINI_KEY (returns null if env not set).
 *   2) Otherwise, the tenant's own gemini_key column.
 *   3) Null if neither path yields a key — caller must error out cleanly.
 *
 * Read on every session start; no caching, since admin can flip the flag
 * mid-day and the next session should pick up the change without a restart.
 */
@Service
public class GeminiKeyResolver {

    public record Resolution(String apiKey, Source source) {}

    public enum Source { TENANT, PLATFORM_DEFAULT, NONE }

    private final TenantDataSources tenantDs;
    private final String platformDefault;

    public GeminiKeyResolver(
        TenantDataSources tenantDs,
        @Value("${pulsar.translate.default-gemini-key:}") String platformDefault
    ) {
        this.tenantDs = tenantDs;
        this.platformDefault = platformDefault == null ? "" : platformDefault;
    }

    public Resolution resolveForDb(String dbName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT gemini_key, use_default_gemini_key FROM translate_config WHERE id = 1");
        if (rows.isEmpty()) return new Resolution(null, Source.NONE);
        Map<String, Object> r = rows.get(0);
        boolean useDefault = boolOr(r.get("use_default_gemini_key"), false);
        if (useDefault) {
            return platformDefault.isBlank()
                ? new Resolution(null, Source.NONE)
                : new Resolution(platformDefault, Source.PLATFORM_DEFAULT);
        }
        String tenantKey = (String) r.get("gemini_key");
        if (tenantKey == null || tenantKey.isBlank()) return new Resolution(null, Source.NONE);
        return new Resolution(tenantKey, Source.TENANT);
    }

    /** Used by the admin UI to show whether the platform default is configured. */
    public boolean platformDefaultAvailable() {
        return !platformDefault.isBlank();
    }

    /**
     * Used by the admin UI to show whether a tenant has its own key set
     * (without exposing the key itself).
     */
    public TenantKeyStatus statusForDb(String dbName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT gemini_key, use_default_gemini_key FROM translate_config WHERE id = 1");
        if (rows.isEmpty()) return new TenantKeyStatus(false, false);
        Map<String, Object> r = rows.get(0);
        String key = (String) r.get("gemini_key");
        return new TenantKeyStatus(
            key != null && !key.isBlank(),
            boolOr(r.get("use_default_gemini_key"), false)
        );
    }

    /**
     * Admin write path. Pass apiKey=null to leave it untouched, "" to clear,
     * or a value to set. Same convention for useDefault.
     */
    public void updateForDb(String dbName, String apiKey, Boolean useDefault) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        // Ensure a row exists so the admin UI is independent of onboarding order.
        jdbc.update(
            "INSERT INTO translate_config (id, gemini_key) VALUES (1, '') "
                + "ON DUPLICATE KEY UPDATE id = id");
        if (apiKey != null) {
            jdbc.update(
                "UPDATE translate_config SET gemini_key = ?, configured_at = COALESCE(configured_at, NOW()) WHERE id = 1",
                apiKey);
        }
        if (useDefault != null) {
            jdbc.update(
                "UPDATE translate_config SET use_default_gemini_key = ? WHERE id = 1",
                useDefault);
        }
    }

    public record TenantKeyStatus(boolean hasTenantKey, boolean useDefault) {}

    private static boolean boolOr(Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return fallback;
    }
}
