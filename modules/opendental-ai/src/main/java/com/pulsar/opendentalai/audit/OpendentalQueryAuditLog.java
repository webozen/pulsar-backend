package com.pulsar.opendentalai.audit;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes one row per {@code run_opendental_query} tool call into the tenant's
 * {@code opendental_ai_query_log} table. Callers pass a per-tenant
 * {@link DataSource} obtained from {@code TenantDataSources.forDb(...)}.
 *
 * <p>Every call — even rejected ones — is recorded so an admin can replay
 * every question ever asked against their practice database.
 */
public class OpendentalQueryAuditLog {

    public record Record(
        String sessionId,
        String userEmail,
        String question,
        String sql,
        Integer rowCount,
        Integer elapsedMs,
        String status,           // 'ok' | 'sql_rejected' | 'od_error' | 'internal_error'
        String errorDetail
    ) {}

    public static void insert(DataSource tenantDs, Record r) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs);
        jdbc.update(
            "INSERT INTO opendental_ai_query_log " +
            "(session_id, user_email, question, sql_text, row_count, elapsed_ms, status, error_detail) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            r.sessionId(), r.userEmail(), r.question(), r.sql(),
            r.rowCount(), r.elapsedMs(), r.status(), r.errorDetail()
        );
    }
}
