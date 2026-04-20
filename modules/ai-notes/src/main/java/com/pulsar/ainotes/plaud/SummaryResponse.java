package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SummaryResponse(
    @JsonProperty("recording_id") String recordingId,
    String content
) {}
