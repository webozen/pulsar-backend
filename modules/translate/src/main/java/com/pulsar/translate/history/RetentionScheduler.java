package com.pulsar.translate.history;

import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.translate.TranslateSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sweep that hard-deletes soft-deleted translate_conversations rows
 * past their tenant's retention window. Runs at 03:30 UTC by default —
 * after most tenants' business hours.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final TenantRepository tenantRepo;
    private final TranslateSettingsService settings;
    private final TranslateConversationsRepository repo;

    public RetentionScheduler(TenantRepository tenantRepo,
                              TranslateSettingsService settings,
                              TranslateConversationsRepository repo) {
        this.tenantRepo = tenantRepo;
        this.settings = settings;
        this.repo = repo;
    }

    @Scheduled(cron = "${pulsar.translate.retention-cron:0 30 3 * * *}", zone = "UTC")
    public void sweep() {
        int total = 0;
        for (var tenant : tenantRepo.findAll()) {
            if (!tenant.activeModules().contains("translate")) continue;
            int retention = settings.forDb(tenant.dbName()).historyRetentionDays();
            try {
                int deleted = repo.hardDeleteExpired(tenant.dbName(), retention);
                if (deleted > 0) {
                    log.info("Retention sweep: db={} retentionDays={} hardDeleted={}", tenant.dbName(), retention, deleted);
                    total += deleted;
                }
            } catch (Exception e) {
                // The translate_conversations table may not exist yet for tenants that
                // were provisioned before V2 migration ran. Log and move on rather
                // than failing the whole sweep.
                log.warn("Retention sweep skipped for db={}: {}", tenant.dbName(), e.getMessage());
            }
        }
        if (total > 0) log.info("Retention sweep complete: hardDeleted={} rows across all tenants", total);
    }
}
