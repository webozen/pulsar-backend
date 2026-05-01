package com.pulsar.translate.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-WS-session accumulator. Captures input-transcription / final translation
 * pairs as they fly past the WS handler, plus session metadata. Persisted on
 * close (if history is enabled for the tenant).
 */
public class TranscriptCollector {

    private final String dbName;
    private final String sourceLang;
    private final String targetLang;
    private final String mode;
    private final Instant startedAt = Instant.now();
    private final List<TranscriptEntry> entries = Collections.synchronizedList(new ArrayList<>());

    public TranscriptCollector(String dbName, String sourceLang, String targetLang, String mode) {
        this.dbName = dbName;
        this.sourceLang = sourceLang;
        this.targetLang = targetLang;
        this.mode = mode;
    }

    public void recordInput(String text) {
        if (text == null || text.isBlank()) return;
        entries.add(new TranscriptEntry("input", text, System.currentTimeMillis()));
    }

    public void recordOutput(String text) {
        if (text == null || text.isBlank()) return;
        entries.add(new TranscriptEntry("output", text, System.currentTimeMillis()));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String dbName() { return dbName; }
    public String sourceLang() { return sourceLang; }
    public String targetLang() { return targetLang; }
    public String mode() { return mode; }
    public Instant startedAt() { return startedAt; }
    public List<TranscriptEntry> snapshot() { return List.copyOf(entries); }
}
