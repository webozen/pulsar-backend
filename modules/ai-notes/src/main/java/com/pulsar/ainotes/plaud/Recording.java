package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Plaud recording metadata in the shape we expose to the frontend.
 *
 * The Plaud upstream uses different field names (start_time / duration /
 * is_trans / is_summary / filetag_id_list); use {@link #fromPlaud(Map)} to
 * adapt those into this canonical record. Don't try to drive deserialization
 * straight from a raw Plaud map — it'll silently break (start_time being
 * epoch-millis was being read as epoch-seconds and producing year-57820 dates).
 */
public record Recording(
    String id,
    String filename,
    @JsonProperty("duration_ms") Long durationMs,
    Long filesize,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("hasTranscription") boolean hasTranscription,
    @JsonProperty("hasSummary") boolean hasSummary,
    @JsonProperty("tag_ids") List<String> tagIds
) {
    public static Recording fromPlaud(Map<String, Object> raw) {
        Object startTime = raw.get("start_time");
        Instant created = null;
        if (startTime instanceof Number n) {
            created = Instant.ofEpochMilli(n.longValue());
        }
        Object duration = raw.get("duration");
        Long durMs = duration instanceof Number n ? n.longValue() : null;
        Object filesize = raw.get("filesize");
        Long size = filesize instanceof Number n ? n.longValue() : null;
        Object name = raw.get("filename");
        if (name == null) name = raw.get("fullname");
        Object isTrans = raw.get("is_trans");
        Object isSummary = raw.get("is_summary");
        Object tagList = raw.get("filetag_id_list");
        List<String> tags = List.of();
        if (tagList instanceof List<?> l) {
            tags = l.stream()
                .filter(o -> o instanceof String)
                .map(Object::toString)
                .toList();
        }
        return new Recording(
            String.valueOf(raw.get("id")),
            name == null ? null : String.valueOf(name),
            durMs,
            size,
            created,
            Boolean.TRUE.equals(isTrans),
            Boolean.TRUE.equals(isSummary),
            tags
        );
    }
}
