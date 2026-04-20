package com.pulsar.ainotes.plaud;

public record FeedItem(
    Recording recording,
    TranscriptResponse transcript,
    SummaryResponse summary
) {}
