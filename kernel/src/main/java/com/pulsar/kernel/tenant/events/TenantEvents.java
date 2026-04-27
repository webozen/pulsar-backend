package com.pulsar.kernel.tenant.events;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycle events published from the tenant provisioning + admin paths.
 * Listeners in any module can subscribe via {@code @EventListener} without
 * the kernel/host-app needing to know about them. The
 * {@code automation-sync} module is the primary consumer today.
 */
public final class TenantEvents {
    private TenantEvents() {}

    public record TenantCreated(long id, String slug, String name, String contactEmail) {}
    public record TenantModulesChanged(long id, String slug, String name, String contactEmail, Set<String> modules) {}
    public record TenantSuspended(long id, String slug) {}
    public record TenantResumed(long id, String slug, String name, String contactEmail, Set<String> modules) {}
    public record TenantDeleted(long id, String slug) {}

    /** Used when a per-tenant module finishes its onboarding flow and wants
     *  the credentials it just collected pushed to the workflow platform's
     *  KV/secrets store. */
    public record TenantSecretsUpdated(long id, String slug, Map<String, String> secrets) {}
}
