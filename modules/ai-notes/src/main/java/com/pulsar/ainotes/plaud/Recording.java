package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record Recording(
    String id,
    String filename,
    @JsonProperty("duration_ms")      Long durationMs,
    Long filesize,
    @JsonProperty("created_at")       Instant createdAt,
    @JsonProperty("has_transcription") boolean hasTranscription,
    @JsonProperty("has_summary")       boolean hasSummary,
    @JsonProperty("tag_ids")           List<String> tagIds
) {}
