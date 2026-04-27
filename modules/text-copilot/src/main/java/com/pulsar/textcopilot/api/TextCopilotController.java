package com.pulsar.textcopilot.api;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.text.TextEvent;
import com.pulsar.kernel.text.TextProvider;
import com.pulsar.kernel.text.TextProviderRegistry;
import com.pulsar.kernel.text.TextSender;
import com.pulsar.kernel.text.TextSender.SendRequest;
import com.pulsar.kernel.text.TextSender.SendResult;
import com.pulsar.textcopilot.services.ReplySuggester;
import com.pulsar.textcopilot.services.ReplySuggester.Suggestion;
import com.pulsar.textintel.services.TextThreadStore;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two endpoints power the SMS composer's AI rail:
 *   POST /api/text-copilot/suggest — returns Gemini's draft alternatives
 *   POST /api/text-copilot/send    — delivers via the active TextSender and
 *                                    persists the outbound message into the
 *                                    same store text-intel reads.
 */
@RestController
@RequestMapping("/api/text-copilot")
@RequireModule("text-support")
public class TextCopilotController {

    private final TenantDataSources tenantDs;
    private final TextProviderRegistry registry;
    private final TextThreadStore store;
    private final ReplySuggester suggester;

    public TextCopilotController(TenantDataSources tenantDs, TextProviderRegistry registry,
                                 TextThreadStore store, ReplySuggester suggester) {
        this.tenantDs = tenantDs;
        this.registry = registry;
        this.store = store;
        this.suggester = suggester;
    }

    public record SuggestRequest(@NotNull Long threadId, String draft, Integer n) {}

    @PostMapping("/suggest")
    public Map<String, Object> suggest(@RequestBody SuggestRequest req) {
        if (req.threadId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_threadId");
        String key = readGeminiKey()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                "opendental_ai_not_onboarded — configure Gemini key first"));
        var msgs = store.recentMessages(req.threadId(), 30);
        if (msgs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no_messages");
        int n = req.n() == null ? 3 : Math.max(1, Math.min(5, req.n()));
        List<Suggestion> out;
        try { out = suggester.suggest(key, msgs, req.draft(), n); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "gemini_failed: " + e.getMessage()); }
        return Map.of("threadId", req.threadId(), "suggestions", out);
    }

    public record SendRequestBody(@NotNull Long threadId, String fromPhone, @NotNull String body) {}

    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody SendRequestBody req) {
        if (req.threadId() == null || req.body() == null || req.body().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_threadId_or_body");
        }
        var t = TenantContext.require();
        DataSource ds = tenantDs.forDb(t.dbName());

        var rows = new JdbcTemplate(ds).queryForList(
            "SELECT provider_id, patient_phone, thread_key FROM text_thread WHERE id = ?",
            req.threadId()
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_thread");
        var thread = rows.get(0);
        String providerId = (String) thread.get("provider_id");
        String toPhone = (String) thread.get("patient_phone");
        String threadKey = (String) thread.get("thread_key");
        if (toPhone == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "thread_has_no_patient_phone");

        TextProvider provider = registry.get(providerId);
        TextSender sender = registry.sender(providerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                "no_sender_for_provider:" + providerId));

        String fromPhone = req.fromPhone();
        if (fromPhone == null || fromPhone.isBlank()) fromPhone = provider.defaultFromPhone(ds);
        if (fromPhone == null || fromPhone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "missing_from_phone — set default_sms_from_phone in voice_provider_config or pass fromPhone");
        }

        SendResult result;
        try { result = sender.send(new SendRequest(fromPhone, toPhone, req.body(), List.of()), ds); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "send_failed: " + e.getMessage()); }

        // Persist the outbound message back into the shared store so text-intel's
        // thread view + summarizer see what we sent.
        store.persist(new TextEvent(
            TextEvent.EventType.SENT, providerId, result.providerMessageId(), threadKey,
            TextEvent.Direction.OUTBOUND, fromPhone, toPhone, req.body(), List.of(), Instant.now()
        ));

        return Map.of(
            "threadId", req.threadId(),
            "providerMessageId", result.providerMessageId() == null ? "" : result.providerMessageId(),
            "status", result.status() == null ? "Sent" : result.status()
        );
    }

    private Optional<String> readGeminiKey() {
        var t = TenantContext.require();
        var rows = new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT gemini_key FROM opendental_ai_config WHERE id = 1"
        );
        if (rows.isEmpty()) return Optional.empty();
        return Optional.ofNullable((String) rows.get(0).get("gemini_key"));
    }
}
