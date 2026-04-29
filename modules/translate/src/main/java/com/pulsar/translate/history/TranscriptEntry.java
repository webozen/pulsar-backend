package com.pulsar.translate.history;

/** One turn in a translate conversation. role is "input" (user audio) or "output" (translated reply). */
public record TranscriptEntry(String role, String text, long ts) {}
