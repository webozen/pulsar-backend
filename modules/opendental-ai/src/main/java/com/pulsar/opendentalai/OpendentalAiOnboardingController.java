package com.pulsar.opendentalai;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.kernel.tenant.events.TenantEvents;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-tenant config save/read for the OpenDental AI module. All three keys
 * (Gemini, OD DeveloperKey, OD CustomerKey) must be set before the chat
 * WebSocket will accept a session for this tenant.
 */
@RestController
@RequestMapping("/api/opendental-ai/config")
@RequireModule("opendental-ai")
public class OpendentalAiOnboardingController {

    private final TenantDataSources tenantDs;
    private final TenantRepository tenantRepo;
    private final ApplicationEventPublisher events;

    public OpendentalAiOnboardingController(
        TenantDataSources tenantDs,
        TenantRepository tenantRepo,
        ApplicationEventPublisher events
    ) {
        this.tenantDs = tenantDs;
        this.tenantRepo = tenantRepo;
        this.events = events;
    }

    /**
     * Wizard payload for OpenDental AI. The Gemini key is NOT collected here —
     * it lives in {@code tenant_credentials} and is set via the admin tenant
     * detail page or the tenant Settings page. (Phase 2 will move OD keys
     * there too.)
     */
    public record ConfigRequest(
        @NotBlank String odDeveloperKey,
        @NotBlank String odCustomerKey
    ) {}

    @GetMapping
    public Map<String, Object> status() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        var rows = jdbc.queryForList("SELECT 1 FROM opendental_ai_config WHERE id = 1");
        return Map.of("onboarded", !rows.isEmpty());
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody ConfigRequest req) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        jdbc.update(
            "INSERT INTO opendental_ai_config (id, od_developer_key, od_customer_key) " +
            "VALUES (1, ?, ?) " +
            "ON DUPLICATE KEY UPDATE od_developer_key = VALUES(od_developer_key), " +
            "od_customer_key = VALUES(od_customer_key)",
            req.odDeveloperKey(), req.odCustomerKey()
        );
        // Push OD secrets to the workflow platform's namespace for dental
        // Kestra flows (recall-reminder etc.). GEMINI_API_KEY is propagated
        // separately by CredentialsService write paths.
        tenantRepo.findBySlug(t.slug()).ifPresent(rec ->
            events.publishEvent(new TenantEvents.TenantSecretsUpdated(rec.id(), rec.slug(), Map.of(
                "OPENDENTAL_DEVELOPER_KEY", req.odDeveloperKey(),
                "OPENDENTAL_CUSTOMER_KEY", req.odCustomerKey()
            )))
        );
        return Map.of("onboarded", true);
    }
}
