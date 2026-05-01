package com.pulsar.translate.history;

import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class TranslateConversationsRepository {

    private final TenantDataSources tenantDs;

    public TranslateConversationsRepository(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public long insert(String dbName, Instant startedAt, Instant endedAt,
                       String sourceLang, String targetLang, String mode,
                       int extendsUsed, byte[] ciphertext, byte[] iv, byte[] tag) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        jdbc.update(
            "INSERT INTO translate_conversations "
                + "(started_at, ended_at, source_lang, target_lang, mode, extends_used, "
                + "transcript_ciphertext, transcript_iv, transcript_tag) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Timestamp.from(startedAt), Timestamp.from(endedAt),
            sourceLang, targetLang, mode, extendsUsed,
            ciphertext, iv, tag
        );
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Map<String, Object>> list(String dbName, int limit, int offset, boolean includeDeleted) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        String where = includeDeleted ? "" : "WHERE deleted_at IS NULL ";
        return jdbc.queryForList(
            "SELECT id, started_at, ended_at, source_lang, target_lang, mode, extends_used, deleted_at "
                + "FROM translate_conversations "
                + where
                + "ORDER BY started_at DESC LIMIT ? OFFSET ?",
            limit, offset);
    }

    public Map<String, Object> findById(String dbName, long id) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, started_at, ended_at, source_lang, target_lang, mode, extends_used, "
                + "transcript_ciphertext, transcript_iv, transcript_tag, deleted_at "
                + "FROM translate_conversations WHERE id = ?",
            id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean softDelete(String dbName, long id) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        int n = jdbc.update(
            "UPDATE translate_conversations SET deleted_at = NOW() WHERE id = ? AND deleted_at IS NULL",
            id);
        return n > 0;
    }

    public boolean restore(String dbName, long id) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        int n = jdbc.update(
            "UPDATE translate_conversations SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL",
            id);
        return n > 0;
    }

    public int hardDeleteExpired(String dbName, int retentionDays) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        return jdbc.update(
            "DELETE FROM translate_conversations "
                + "WHERE deleted_at IS NOT NULL AND deleted_at < (NOW() - INTERVAL ? DAY)",
            retentionDays);
    }
}
