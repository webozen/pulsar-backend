package com.pulsar.automationsync.client;

import com.pulsar.automationsync.client.AutomationPlatformClient.Result;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures sync calls that didn't reach the flow-platform and replays them.
 *
 * <p>Lives in the platform datasource (not per-tenant), so all retries for
 * all tenants share one queue. The {@code @Scheduled} retry loop runs every
 * minute and walks the oldest 50 unfinished rows. A row is considered
 * finished when its {@code resolved_at} is NOT NULL.
 */
@Service
public class TenantSyncAuditService {

    private static final Logger log = LoggerFactory.getLogger(TenantSyncAuditService.class);
    private static final int RETRY_BATCH = 50;
    private static final int MAX_ATTEMPTS = 12;   // ~12 min before we give up

    private final JdbcTemplate jdbc;
    private final AutomationPlatformClient client;

    public TenantSyncAuditService(DataSource platformDs, AutomationPlatformClient client) {
        this.jdbc = new JdbcTemplate(platformDs);
        this.client = client;
    }

    /** Record a result. Successful results are stored as resolved-immediately
     *  for traceability; failures wait for the retry loop. */
    public void record(String slug, Result r) {
        boolean resolved = r.ok();
        jdbc.update(
            "INSERT INTO tenant_sync_audit (slug, request_path, request_json, last_status, last_error, attempts, resolved_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, " + (resolved ? "NOW()" : "NULL") + ")",
            slug, r.requestPath(), r.requestJson(), r.status(), r.error(), 1
        );
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    @Transactional
    public void retryFailures() {
        var rows = jdbc.queryForList(
            "SELECT id, slug, request_path, request_json, attempts FROM tenant_sync_audit " +
            "WHERE resolved_at IS NULL AND attempts < ? ORDER BY id LIMIT ?",
            MAX_ATTEMPTS, RETRY_BATCH
        );
        if (rows.isEmpty()) return;
        log.info("automation sync retry batch: {} pending", rows.size());
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String slug = (String) row.get("slug");
            String path = (String) row.get("request_path");
            String json = (String) row.get("request_json");
            int attempts = ((Number) row.get("attempts")).intValue();
            Result r = client.replay(path, json);
            if (r.ok()) {
                jdbc.update("UPDATE tenant_sync_audit SET resolved_at = NOW(), attempts = ?, last_status = ?, last_error = NULL WHERE id = ?",
                    attempts + 1, r.status(), id);
                log.info("automation sync retry succeeded: slug={} path={} attempt={}", slug, path, attempts + 1);
            } else {
                jdbc.update("UPDATE tenant_sync_audit SET attempts = ?, last_status = ?, last_error = ? WHERE id = ?",
                    attempts + 1, r.status(), r.error(), id);
            }
        }
    }
}
