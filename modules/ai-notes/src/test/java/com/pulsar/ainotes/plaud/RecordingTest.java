package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void fromPlaudMapsRealApiShape() {
        Map<String, Object> raw = Map.of(
            "id", "abc123",
            "filename", "morning-huddle.m4a",
            "fullname", "morning-huddle",
            "duration", 33000,
            "filesize", 132000,
            "start_time", 1762460486000L,
            "is_trans", true,
            "is_summary", true,
            "filetag_id_list", List.of("tag1", "tag2")
        );

        Recording r = Recording.fromPlaud(raw);

        assertEquals("abc123", r.id());
        assertEquals("morning-huddle.m4a", r.filename());
        assertEquals(33000L, r.durationMs());
        assertEquals(132000L, r.filesize());
        assertEquals(Instant.ofEpochMilli(1762460486000L), r.createdAt());
        assertTrue(r.hasTranscription());
        assertTrue(r.hasSummary());
        assertEquals(List.of("tag1", "tag2"), r.tagIds());
    }

    @Test
    void startTimeIsTreatedAsMillisNotSeconds() {
        // Regression for the year-57820 bug: 1762460486000 is millis-since-epoch
        // and must produce a 2025 date, not be reinterpreted as seconds.
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "x");
        raw.put("filename", "x.m4a");
        raw.put("start_time", 1762460486000L);
        Recording r = Recording.fromPlaud(raw);
        assertTrue(r.createdAt().isBefore(Instant.parse("2030-01-01T00:00:00Z")),
            "start_time must be parsed as millis, not seconds");
        assertTrue(r.createdAt().isAfter(Instant.parse("2020-01-01T00:00:00Z")),
            "start_time must produce a sane recent date");
    }

    @Test
    void missingTransAndSummaryFlagsDefaultToFalse() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "y");
        raw.put("filename", "y.m4a");
        raw.put("start_time", 1762460486000L);
        Recording r = Recording.fromPlaud(raw);
        assertFalse(r.hasTranscription());
        assertFalse(r.hasSummary());
    }

    @Test
    void missingTagListBecomesEmptyNotNull() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "z");
        raw.put("filename", "z.m4a");
        raw.put("start_time", 1762460486000L);
        Recording r = Recording.fromPlaud(raw);
        assertNotNull(r.tagIds());
        assertTrue(r.tagIds().isEmpty());
    }

    @Test
    void fullnameIsUsedWhenFilenameMissing() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "y2");
        raw.put("fullname", "fallback-name");
        raw.put("start_time", 1762460486000L);
        Recording r = Recording.fromPlaud(raw);
        assertEquals("fallback-name", r.filename());
    }

    @Test
    void serializesToFrontendFriendlyShape() throws Exception {
        Recording r = new Recording(
            "id", "name", 1000L, 2000L,
            Instant.parse("2026-04-26T12:00:00Z"),
            true, false, List.of("a")
        );
        String json = mapper.writeValueAsString(r);
        assertTrue(json.contains("\"created_at\""));
        assertTrue(json.contains("\"hasTranscription\":true"));
        assertTrue(json.contains("\"hasSummary\":false"));
        assertTrue(json.contains("\"tag_ids\":[\"a\"]"));
        assertTrue(json.contains("\"duration_ms\":1000"));
    }

    @Test
    void numericFieldsAccept_int_long_double() {
        // Plaud sometimes returns numbers as int, sometimes as long, depending
        // on the JSON parser path. fromPlaud must handle all Number subtypes.
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "n");
        raw.put("filename", "n.m4a");
        raw.put("duration", (int) 33000);
        raw.put("filesize", 132000.0);
        raw.put("start_time", 1762460486000L);
        Recording r = Recording.fromPlaud(raw);
        assertEquals(33000L, r.durationMs());
        assertEquals(132000L, r.filesize());
    }
}
