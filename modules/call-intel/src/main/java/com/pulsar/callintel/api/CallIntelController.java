package com.pulsar.callintel.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.callintel.services.GeminiSummarizer;
import com.pulsar.callintel.services.GeminiSummarizer.Summary;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.voice.CallEndedEvent;
import com.pulsar.kernel.voice.RecordingFetcher;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

/**
 * Post-call intelligence endpoints. Provider-neutral: the same controller
 * handles RingCentral, Twilio, Zoom Phone, etc.
 *
 * <p>Ingest paths:
 * <ul>
 *   <li>{@code POST /api/call-intel/process} — manual trigger (transcript or
 *       recording URL); used in dev and for backfills regardless of provider.</li>
 *   <li>{@link #onCallEnded(CallEndedEvent)} — Spring event listener that
 *       receives {@link CallEndedEvent} published by the kernel
 *       {@code VoiceWebhookController} and inserts into
 *       {@code call_intel_webhook_queue}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/call-intel")
@RequireModule("call-handling")
public class CallIntelController {

    private final TenantDataSources tenantDs;
    private final GeminiSummarizer summarizer;
    private final Map<String, RecordingFetcher> recordingFetchersById;
    private final ObjectMapper mapper = new ObjectMapper();

    public CallIntelController(
        TenantDataSources tenantDs,
        GeminiSummarizer summarizer,
        List<RecordingFetcher> recordingFetchers
    ) {
        this.tenantDs = tenantDs;
        this.summarizer = summarizer;
        this.recordingFetchersById = recordingFetchers.stream()
            .collect(Collectors.toMap(RecordingFetcher::id, f -> f));
    }

    public record ProcessRequest(
        String providerId,             // optional; defaults to "ringcentral" for back-compat
        String providerSessionId,      // was rcSessionId
        String direction,
        String callerPhone,
        Integer durationSec,
        String recordingRef,           // adapter-specific opaque ref (URL or id)
        String recordingUrl,           // legacy alias for recordingRef
        String recordingAuthHeader,    // dev-only override; bypasses RecordingFetcher
        String recordingMimeType,
        String transcript
    ) {}

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> process(@Valid @RequestBody ProcessRequest req) {
        String ref = firstNonBlank(req.recordingRef(), req.recordingUrl());
        if ((ref == null || ref.isBlank())
            && (req.transcript() == null || req.transcript().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "must provide recordingRef/recordingUrl or transcript");
        }

        String geminiKey = readGeminiKey()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                "opendental_ai_not_onboarded — configure Gemini key first"));

        String providerId = req.providerId() == null || req.providerId().isBlank()
            ? "ringcentral" : req.providerId();

        Summary s;
        try {
            if (req.transcript() != null && !req.transcript().isBlank()) {
                s = summarizer.summarizeTranscript(geminiKey, req.transcript());
            } else {
                byte[] audio = fetchRecording(providerId, ref, req.recordingAuthHeader());
                String mime = req.recordingMimeType() == null ? "audio/mpeg" : req.recordingMimeType();
                s = summarizer.summarizeAudio(geminiKey, audio, mime);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "gemini_or_recording_failed: " + e.getMessage());
        }

        long id = writeEntry(providerId, ref, req, s);
        return ResponseEntity.ok(Map.of(
            "id", id,
            "providerId", providerId,
            "summary", s.summary(),
            "actionItems", s.actionItems(),
            "sentiment", s.sentiment(),
            "patientIntent", s.patientIntent()
        ));
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> list() {
        var t = TenantContext.require();
        return new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT id, provider_id, provider_session_id, direction, caller_phone, " +
            "       duration_sec, summary, sentiment, patient_intent, created_at " +
            "FROM call_intel_entry ORDER BY created_at DESC LIMIT 100"
        );
    }

    @GetMapping("/entries/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        var t = TenantContext.require();
        var rows = new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT * FROM call_intel_entry WHERE id = ?", id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    // ───────────────────────────────────────────────────────────────────────

    private java.util.Optional<String> readGeminiKey() {
        var t = TenantContext.require();
        var rows = new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT gemini_key FROM opendental_ai_config WHERE id = 1"
        );
        if (rows.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable((String) rows.get(0).get("gemini_key"));
    }

    /** Try the kernel adapter first; fall back to the dev-friendly raw fetch
     *  with optional Authorization header (still used by the test modal that
     *  pastes any public mp3 URL). */
    private byte[] fetchRecording(String providerId, String ref, String authHeaderOverride) throws IOException {
        if (authHeaderOverride != null && !authHeaderOverride.isBlank()) {
            return rawDownload(ref, authHeaderOverride);
        }
        RecordingFetcher fetcher = recordingFetchersById.get(providerId);
        if (fetcher != null) {
            var t = TenantContext.require();
            return fetcher.fetch(ref, tenantDs.forDb(t.dbName()));
        }
        // No adapter and no override → assume the ref is a public URL.
        return rawDownload(ref, null);
    }

    private static byte[] rawDownload(String url, String authHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (authHeader != null && !authHeader.isBlank()) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        int status = conn.getResponseCode();
        if (status >= 400) throw new IOException("recording_fetch_" + status);
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private long writeEntry(String providerId, String recordingRef, ProcessRequest req, Summary s) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        String actionItemsJson;
        try { actionItemsJson = mapper.writeValueAsString(s.actionItems()); }
        catch (JsonProcessingException e) { actionItemsJson = "[]"; }
        jdbc.update(
            "INSERT INTO call_intel_entry (provider_id, provider_session_id, direction, caller_phone, " +
            "  duration_sec, recording_url, transcript, summary, action_items, " +
            "  sentiment, patient_intent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            providerId,
            req.providerSessionId(),
            req.direction() == null ? "unknown" : req.direction(),
            req.callerPhone(),
            req.durationSec(),
            recordingRef,
            s.transcript(),
            s.summary(),
            actionItemsJson,
            s.sentiment(),
            s.patientIntent()
        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0 : id;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    @org.springframework.context.event.EventListener
    public void onCallEnded(CallEndedEvent event) {
        try {
            javax.sql.DataSource ds = tenantDs.forDb(event.dbName());
            new JdbcTemplate(ds).update(
                "INSERT INTO call_intel_webhook_queue (provider_id, raw_payload) VALUES (?, ?)",
                event.providerId(), event.rawPayload()
            );
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(CallIntelController.class)
                .warn("Failed to queue call-ended event for {}: {}", event.tenantSlug(), e.toString());
        }
    }
}
