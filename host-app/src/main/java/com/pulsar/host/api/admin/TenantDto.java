package com.pulsar.host.api.admin;

import com.pulsar.kernel.tenant.TenantRecord;
import java.time.Instant;
import java.util.Set;

public record TenantDto(
    long id,
    String slug,
    String name,
    Set<String> modules,
    String contactEmail,
    String passcode,
    Instant suspendedAt,
    Instant createdAt
) {
    public static TenantDto of(TenantRecord r) {
        return new TenantDto(
            r.id(),
            r.slug(),
            r.name(),
            r.activeModules(),
            r.contactEmail(),
            r.passcode(),
            r.suspendedAt(),
            r.createdAt()
        );
    }
}
