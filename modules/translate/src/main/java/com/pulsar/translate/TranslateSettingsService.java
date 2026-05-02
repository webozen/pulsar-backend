package com.pulsar.translate;

import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Reads/writes the per-tenant translate_config overlay and merges it with
 * platform defaults from env. Used by the WS handler (effective settings),
 * the tenant settings page (read-only display), and the admin endpoint
 * (read + write).
 */
@Service
public class TranslateSettingsService {

    private final TenantDataSources tenantDs;
    private final int defaultSessionDurationMin;
    private final int defaultExtendGrantMin;
    private final int defaultMaxExtends;
    private final boolean defaultHistoryEnabled;
    private final int defaultRetentionDays;

    public TranslateSettingsService(
        TenantDataSources tenantDs,
        @Value("${pulsar.translate.session-duration-min:30}") int defaultSessionDurationMin,
        @Value("${pulsar.translate.extend-grant-min:30}") int defaultExtendGrantMin,
        @Value("${pulsar.translate.max-extends:3}") int defaultMaxExtends,
        @Value("${pulsar.translate.history-enabled:false}") boolean defaultHistoryEnabled,
        @Value("${pulsar.translate.history-retention-days:90}") int defaultRetentionDays
    ) {
        this.tenantDs = tenantDs;
        this.defaultSessionDurationMin = defaultSessionDurationMin;
        this.defaultExtendGrantMin = defaultExtendGrantMin;
        this.defaultMaxExtends = defaultMaxExtends;
        this.defaultHistoryEnabled = defaultHistoryEnabled;
        this.defaultRetentionDays = defaultRetentionDays;
    }

    public TranslateSettings forDb(String dbName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT session_duration_min, extend_grant_min, max_extends, history_enabled, history_retention_days "
                + "FROM translate_config WHERE id = 1");
        if (rows.isEmpty()) {
            return defaults();
        }
        Map<String, Object> r = rows.get(0);
        return new TranslateSettings(
            intOr(r.get("session_duration_min"), defaultSessionDurationMin),
            intOr(r.get("extend_grant_min"), defaultExtendGrantMin),
            intOr(r.get("max_extends"), defaultMaxExtends),
            boolOr(r.get("history_enabled"), defaultHistoryEnabled),
            intOr(r.get("history_retention_days"), defaultRetentionDays)
        );
    }

    /**
     * Returns the platform defaults — used by the admin UI to show what an
     * empty per-tenant override resolves to.
     */
    public TranslateSettings defaults() {
        return new TranslateSettings(
            defaultSessionDurationMin,
            defaultExtendGrantMin,
            defaultMaxExtends,
            defaultHistoryEnabled,
            defaultRetentionDays
        );
    }

    /**
     * Returns the raw per-tenant override row (nulls preserved) so admins can
     * see "blank means default" vs an explicit value.
     */
    public Map<String, Object> rawOverridesForDb(String dbName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT session_duration_min, extend_grant_min, max_extends, history_enabled, "
                + "history_retention_days, settings_updated_at FROM translate_config WHERE id = 1");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * Update one or more per-tenant overrides. Pass null to clear an override
     * (so the platform default takes effect for that field).
     */
    public void updateOverridesForDb(
        String dbName,
        Integer sessionDurationMin,
        Integer extendGrantMin,
        Integer maxExtends,
        Boolean historyEnabled,
        Integer historyRetentionDays
    ) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        // Ensure a row exists so settings can be pre-set before onboarding runs.
        jdbc.update(
            "INSERT INTO translate_config (id) VALUES (1) "
                + "ON DUPLICATE KEY UPDATE id = id");
        jdbc.update(
            "UPDATE translate_config SET "
                + "session_duration_min = ?, extend_grant_min = ?, max_extends = ?, "
                + "history_enabled = ?, history_retention_days = ?, settings_updated_at = NOW() "
                + "WHERE id = 1",
            sessionDurationMin,
            extendGrantMin,
            maxExtends,
            historyEnabled,
            historyRetentionDays
        );
    }

    private static int intOr(Object v, int fallback) {
        return v == null ? fallback : ((Number) v).intValue();
    }

    private static boolean boolOr(Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return fallback;
    }
}
