package com.pulsar.automationsync.listener;

import com.pulsar.automationsync.client.AutomationPlatformClient;
import com.pulsar.automationsync.client.AutomationPlatformClient.Result;
import com.pulsar.automationsync.client.TenantSyncAuditService;
import com.pulsar.kernel.tenant.events.TenantEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Single subscriber for every Pulsar tenant lifecycle event. Translates each
 * event into the matching {@link AutomationPlatformClient} call and routes
 * the {@link Result} through the audit service so retries are guaranteed.
 *
 * <p>Listeners run AFTER_COMMIT so a flow-platform outage (slow OkHttp call
 * or audit-table failure) cannot roll back the originating Pulsar tenant
 * write. {@code fallbackExecution = true} keeps the current behavior when
 * the publisher isn't inside a transaction — the listener still fires
 * synchronously, admins still see immediate feedback. Each handler swallows
 * exceptions so a downstream failure surfaces in logs + the audit row but
 * never as a 500 on the admin's HTTP response.
 */
@Component
public class TenantLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleListener.class);

    private final AutomationPlatformClient client;
    private final TenantSyncAuditService audit;

    public TenantLifecycleListener(AutomationPlatformClient client, TenantSyncAuditService audit) {
        this.client = client;
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(TenantEvents.TenantCreated e) {
        runSafely("provision", e.slug(), () -> {
            Result r = client.provision(e.slug(), e.name(), e.contactEmail(), java.util.Set.of());
            audit.record(e.slug(), r);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onModulesChanged(TenantEvents.TenantModulesChanged e) {
        // provision is idempotent and ALSO deploys flows that haven't shipped
        // yet — important for tenants whose lifecycle started before
        // automation-sync was wired up (no original "provision" call to
        // bootstrap their Kestra namespace). update-modules alone would only
        // toggle existing flows and leave the namespace empty.
        runSafely("provision-on-module-change", e.slug(), () -> {
            Result r = client.provision(e.slug(), e.name(), e.contactEmail(), e.modules());
            audit.record(e.slug(), r);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSuspended(TenantEvents.TenantSuspended e) {
        runSafely("suspend", e.slug(), () -> {
            Result r = client.suspend(e.slug());
            audit.record(e.slug(), r);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResumed(TenantEvents.TenantResumed e) {
        runSafely("resume", e.slug(), () -> {
            Result r = client.resume(e.slug(), e.modules());
            audit.record(e.slug(), r);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(TenantEvents.TenantDeleted e) {
        runSafely("delete", e.slug(), () -> {
            Result r = client.delete(e.slug());
            audit.record(e.slug(), r);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSecretsUpdated(TenantEvents.TenantSecretsUpdated e) {
        runSafely("push-secrets", e.slug(), () -> {
            Result r = client.pushSecrets(e.slug(), e.secrets());
            audit.record(e.slug(), r);
        });
    }

    private void runSafely(String op, String slug, Runnable body) {
        log.info("automation-sync: {} slug={}", op, slug);
        try {
            body.run();
        } catch (RuntimeException ex) {
            // Swallow — a flow-platform call already returns Result without
            // throwing, so the only way here is the audit insert itself
            // failing (e.g., MySQL hiccup). Logging + the audit retry loop
            // are the safety net; we must not propagate to the admin's HTTP
            // response, which is already committed by AFTER_COMMIT timing.
            log.warn("automation-sync: {} failed slug={}: {}", op, slug, ex.toString());
        }
    }
}
