package com.pulsar.textintel.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.text.TextEvent;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persists incoming/outgoing text messages into the per-tenant {@code
 * text_thread} + {@code text_message} tables. Idempotent: re-receiving the
 * same {@code provider_message_id} is a no-op.
 *
 * Shared by both text-intel (webhook ingestion) and text-copilot (writing the
 * staff's outbound reply when send succeeds).
 */
@Component
public class TextThreadStore {

    private final TenantDataSources tenantDs;
    private final ObjectMapper mapper = new ObjectMapper();

    public TextThreadStore(TenantDataSources tenantDs) { this.tenantDs = tenantDs; }

    /** Upsert thread + message; returns thread_id. */
    public long persist(TextEvent ev) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));

        String patientPhone = ev.direction() == TextEvent.Direction.INBOUND ? ev.fromPhone() : ev.toPhone();
        jdbc.update(
            "INSERT INTO text_thread (provider_id, thread_key, patient_phone, last_message_at, message_count) " +
            "VALUES (?, ?, ?, ?, 1) " +
            "ON DUPLICATE KEY UPDATE " +
            "  last_message_at = GREATEST(COALESCE(last_message_at, '1970-01-01'), VALUES(last_message_at)), " +
            "  message_count   = message_count + 1, " +
            "  patient_phone   = COALESCE(patient_phone, VALUES(patient_phone))",
            ev.providerId(), ev.threadKey(), patientPhone,
            ev.sentAt() == null ? new java.sql.Timestamp(System.currentTimeMillis()) : java.sql.Timestamp.from(ev.sentAt())
        );
        Long threadId = jdbc.queryForObject(
            "SELECT id FROM text_thread WHERE provider_id = ? AND thread_key = ?",
            Long.class, ev.providerId(), ev.threadKey()
        );
        if (threadId == null) return 0;

        // Skip duplicate message ids (replays).
        if (ev.providerMessageId() != null) {
            var dup = jdbc.queryForList(
                "SELECT 1 FROM text_message WHERE provider_message_id = ? LIMIT 1",
                ev.providerMessageId()
            );
            if (!dup.isEmpty()) return threadId;
        }

        String mediaJson = "[]";
        try { mediaJson = mapper.writeValueAsString(ev.mediaUrls() == null ? List.of() : ev.mediaUrls()); }
        catch (JsonProcessingException ignored) {}

        jdbc.update(
            "INSERT INTO text_message (thread_id, provider_message_id, direction, from_phone, to_phone, " +
            " body, media_urls, status, sent_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)",
            threadId,
            ev.providerMessageId(),
            ev.direction() == TextEvent.Direction.INBOUND ? "inbound" : "outbound",
            ev.fromPhone(), ev.toPhone(), ev.body(), mediaJson,
            ev.eventType() == null ? "received" : ev.eventType().name().toLowerCase(),
            ev.sentAt() == null ? null : java.sql.Timestamp.from(ev.sentAt())
        );
        return threadId;
    }

    /** Fetch the last N messages of a thread, oldest first — used by both summarizer and copilot. */
    public List<Map<String, Object>> recentMessages(long threadId, int limit) {
        var t = TenantContext.require();
        return new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT id, direction, from_phone, to_phone, body, sent_at " +
            "FROM (SELECT * FROM text_message WHERE thread_id = ? ORDER BY sent_at DESC, id DESC LIMIT ?) sub " +
            "ORDER BY sent_at ASC, id ASC",
            threadId, limit
        );
    }
}
