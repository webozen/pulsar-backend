package com.pulsar.kernel.tenant;

import java.time.Instant;
import java.util.Set;

public record TenantRecord(
    long id,
    String slug,
    String name,
    String dbName,
    Set<String> activeModules,
    String contactEmail,
    String passcode,
    Instant suspendedAt,
    Instant createdAt
) {
    public boolean suspended() { return suspendedAt != null; }
}
