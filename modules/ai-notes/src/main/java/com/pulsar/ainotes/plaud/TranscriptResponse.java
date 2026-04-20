package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TranscriptResponse(
    @JsonProperty("recording_id") String recordingId,
    List<Object> segments
) {}
