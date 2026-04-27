package com.pulsar.kernel.voice;

public record CallRingingEvent(
    String tenantSlug,
    String dbName,
    String callerPhone,
    String sessionId,
    String direction    // "INBOUND" | "OUTBOUND" | "UNKNOWN"
) {}
