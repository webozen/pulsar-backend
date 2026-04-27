package com.pulsar.kernel.voice;

public record CallEndedEvent(
    String tenantSlug,
    String dbName,
    String providerId,
    String rawPayload   // JSON string of original webhook payload
) {}
