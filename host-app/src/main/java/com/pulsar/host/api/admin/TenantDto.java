package com.pulsar.host.api.admin;

import com.pulsar.kernel.tenant.TenantRecord;
import java.time.Instant;
import java.util.Set;

/**
 * Response shape for tenant listing/update endpoints.
 *
 * <p><strong>Does NOT include the access passcode</strong>. The passcode is
 * returned exactly once, at creation time, via {@link
 * AdminTenantsController.CreateTenantResponse}. After that it is write-only:
 * admins rotate a new passcode via {@code POST /{id}/passcode} and hand it to
 * the tenant out-of-band. Returning it in every list response makes the
 * admin UI a full credential disclosure.
 */
public record TenantDto(
    long id,
    String slug,
    String name,
    Set<String> modules,
    String contactEmail,
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
            r.suspendedAt(),
            r.createdAt()
        );
    }
}
