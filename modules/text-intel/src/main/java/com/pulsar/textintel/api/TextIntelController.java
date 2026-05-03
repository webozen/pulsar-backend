package com.pulsar.textintel.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.text.TextEvent;
import com.pulsar.kernel.text.TextProviderRegistry;
import com.pulsar.kernel.text.TextWebhookAdapter;
import com.pulsar.textintel.services.TextSummarizer;
import com.pulsar.textintel.services.TextSummarizer.Summary;
import com.pulsar.textintel.services.TextThreadStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/text-intel")
@RequireModule("text-support")
public class TextIntelController {

    private final TenantDataSources tenantDs;
    private final TextProviderRegistry registry;
    private final TextThreadStore store;
    private final TextSummarizer summarizer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiKeyResolver geminiKeyResolver;

    public TextIntelController(TenantDataSources tenantDs, TextProviderRegistry registry,
                               TextThreadStore store, TextSummarizer summarizer,
                               GeminiKeyResolver geminiKeyResolver) {
        this.tenantDs = tenantDs;
        this.registry = registry;
        this.store = store;
        this.summarizer = summarizer;
        this.geminiKeyResolver = geminiKeyResolver;
    }

    /** Provider-neutral inbound webhook: /webhook/text/{providerId}. */
    @PostMapping("/webhook/text/{providerId}")
    public ResponseEntity<Map<String, Object>> webhook(
        @PathVariable String providerId,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest httpReq
    ) {
        String validation = httpReq.getHeader("Validation-Token");
        if (validation != null) {
            return ResponseEntity.ok().header("Validation-Token", validation).body(Map.of("ok", true));
        }
        TextWebhookAdapter adapter = registry.webhook(providerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_provider:" + providerId));

        var t = TenantContext.require();
        DataSource ds = tenantDs.forDb(t.dbName());
        Map<String, String> headers = headerMap(httpReq);
        if (!adapter.validateSignature(payload, headers, ds)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_signature");
        }

        try {
            new JdbcTemplate(ds).update(
                "INSERT INTO text_intel_webhook_queue (provider_id, raw_payload) VALUES (?, ?)",
                providerId, mapper.writeValueAsString(payload)
            );
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payload");
        }

        // Same-process drain — also persist to thread store immediately so the
        // UI doesn't wait on a separate worker. The queue row is the audit trail.
        Optional<TextEvent> ev = adapter.parse(payload, headers);
        Long threadId = ev.map(store::persist).orElse(null);

        return ResponseEntity.ok(Map.of(
            "queued", true,
            "providerId", providerId,
            "threadId", threadId == null ? "" : threadId.toString()
        ));
    }

    public record ProcessRequest(@jakarta.validation.constraints.NotNull Long threadId, Integer messageLimit) {}

    /** Generate / regenerate the Gemini summary for a thread. */
    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody ProcessRequest req) {
        if (req.threadId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_threadId");
        var geminiKey = readGeminiKey()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                "opendental_ai_not_onboarded — configure Gemini key first"));
        int limit = req.messageLimit() == null ? 50 : req.messageLimit();
        var msgs = store.recentMessages(req.threadId(), limit);
        if (msgs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no_messages_for_thread");
        Summary s;
        try { s = summarizer.summarize(geminiKey, msgs); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "gemini_failed: " + e.getMessage()); }
        writeSummary(req.threadId(), msgs, s);
        return Map.of(
            "threadId", req.threadId(),
            "summary", s.summary(),
            "sentiment", s.sentiment(),
            "intent", s.intent(),
            "actionItems", s.actionItems()
        );
    }

    @GetMapping("/threads")
    public List<Map<String, Object>> threads() {
        var t = TenantContext.require();
        return new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT t.id, t.provider_id, t.thread_key, t.patient_phone, t.last_message_at, t.message_count, " +
            "       s.summary, s.sentiment, s.intent " +
            "FROM text_thread t LEFT JOIN text_intel_summary s ON s.thread_id = t.id " +
            "ORDER BY t.last_message_at DESC LIMIT 100"
        );
    }

    @GetMapping("/threads/{id}")
    public Map<String, Object> thread(@PathVariable long id) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        var threads = jdbc.queryForList(
            "SELECT t.*, s.summary, s.sentiment, s.intent, s.action_items, s.generated_at " +
            "FROM text_thread t LEFT JOIN text_intel_summary s ON s.thread_id = t.id WHERE t.id = ?", id
        );
        if (threads.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var msgs = jdbc.queryForList(
            "SELECT id, direction, from_phone, to_phone, body, sent_at, status FROM text_message " +
            "WHERE thread_id = ? ORDER BY sent_at ASC, id ASC", id
        );
        var out = new java.util.HashMap<String, Object>(threads.get(0));
        out.put("messages", msgs);
        return out;
    }

    // ───────────────────────────────────────────────────────────────────────

    private Optional<String> readGeminiKey() {
        var t = TenantContext.require();
        String key = geminiKeyResolver.resolveForDb(t.dbName()).apiKey();
        return key == null || key.isBlank() ? Optional.empty() : Optional.of(key);
    }

    private void writeSummary(long threadId, List<Map<String, Object>> msgs, Summary s) {
        var t = TenantContext.require();
        // MySQL's `bigint unsigned` comes back as BigInteger via JDBC, not Long;
        // cast through Number so any numeric type works.
        Object rawId = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1).get("id");
        Long lastId = rawId instanceof Number n ? n.longValue() : null;
        String actionsJson = "[]";
        try { actionsJson = mapper.writeValueAsString(s.actionItems()); }
        catch (JsonProcessingException ignored) {}
        new JdbcTemplate(tenantDs.forDb(t.dbName())).update(
            "INSERT INTO text_intel_summary (thread_id, summary, sentiment, intent, action_items, last_message_id) " +
            "VALUES (?, ?, ?, ?, CAST(? AS JSON), ?) " +
            "ON DUPLICATE KEY UPDATE summary = VALUES(summary), sentiment = VALUES(sentiment), " +
            "  intent = VALUES(intent), action_items = VALUES(action_items), " +
            "  last_message_id = VALUES(last_message_id), generated_at = CURRENT_TIMESTAMP",
            threadId, s.summary(), s.sentiment(), s.intent(), actionsJson, lastId
        );
    }

    private static Map<String, String> headerMap(HttpServletRequest req) {
        var names = req.getHeaderNames();
        if (names == null) return Collections.emptyMap();
        return Collections.list(names).stream()
            .collect(Collectors.toMap(n -> n, req::getHeader, (a, b) -> a));
    }
}
