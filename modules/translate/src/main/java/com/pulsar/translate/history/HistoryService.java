package com.pulsar.translate.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.translate.TranslateSettings;
import com.pulsar.translate.TranslateSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation-history orchestrator. The WS handler calls start() at session
 * begin, recordInput()/recordOutput() per turn, and finalize() on close.
 *
 * Persistence is gated by:
 *   1) Tenant has history_enabled (per-tenant override OR platform default).
 *   2) PULSAR_TRANSLATE_HISTORY_KEY is configured (fail-closed otherwise).
 *
 * The crypto helper is constructed lazily so the app can boot without a
 * history key — history just stays disabled at runtime in that case.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final TranslateSettingsService settingsService;
    private final TranslateConversationsRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TranslateCrypto crypto;
    private final boolean enabledGlobal;

    public HistoryService(
        TranslateSettingsService settingsService,
        TranslateConversationsRepository repo,
        @Value("${pulsar.translate.history-key:}") String historyKey
    ) {
        this.settingsService = settingsService;
        this.repo = repo;
        if (historyKey == null || historyKey.isBlank()) {
            this.crypto = null;
            this.enabledGlobal = false;
            log.warn("Translate history disabled — PULSAR_TRANSLATE_HISTORY_KEY not configured");
        } else {
            TranslateCrypto c = null;
            try {
                c = new TranslateCrypto(historyKey);
            } catch (Exception e) {
                log.error("Translate history key invalid — history disabled: {}", e.getMessage());
            }
            this.crypto = c;
            this.enabledGlobal = c != null;
        }
    }

    public TranscriptCollector start(String dbName, String sourceLang, String targetLang, String mode) {
        return new TranscriptCollector(dbName, sourceLang, targetLang, mode);
    }

    public void finalize(TranscriptCollector collector, int extendsUsed) {
        if (collector == null || collector.isEmpty()) return;
        if (!enabledGlobal) return;

        TranslateSettings settings = settingsService.forDb(collector.dbName());
        if (!settings.historyEnabled()) {
            log.debug("History disabled for tenant db={}; skipping persist", collector.dbName());
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("entries", collector.snapshot());
            byte[] plaintext = mapper.writeValueAsBytes(payload);
            TranslateCrypto.Encrypted enc = crypto.encrypt(plaintext);

            long id = repo.insert(
                collector.dbName(),
                collector.startedAt(),
                Instant.now(),
                collector.sourceLang(),
                collector.targetLang(),
                collector.mode(),
                extendsUsed,
                enc.ciphertext(), enc.iv(), enc.tag()
            );
            log.info("Persisted translate conversation: db={} id={} entries={} extends={}",
                collector.dbName(), id, collector.snapshot().size(), extendsUsed);
        } catch (Exception e) {
            log.error("Failed to persist translate conversation: db={} err={}", collector.dbName(), e.getMessage(), e);
        }
    }

    public Map<String, Object> decryptTranscript(Map<String, Object> row) {
        if (crypto == null) return Map.of("entries", List.of());
        byte[] ct = (byte[]) row.get("transcript_ciphertext");
        byte[] iv = (byte[]) row.get("transcript_iv");
        byte[] tag = (byte[]) row.get("transcript_tag");
        if (ct == null || iv == null || tag == null) return Map.of("entries", List.of());
        try {
            byte[] plaintext = crypto.decrypt(ct, iv, tag);
            return mapper.readValue(new String(plaintext, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            log.error("Failed to decrypt transcript: {}", e.getMessage());
            return Map.of("entries", List.of(), "error", "decrypt_failed");
        }
    }

    public boolean isAvailable() { return enabledGlobal; }

    public TranslateConversationsRepository repo() { return repo; }
    public TranslateSettingsService settings() { return settingsService; }
}
