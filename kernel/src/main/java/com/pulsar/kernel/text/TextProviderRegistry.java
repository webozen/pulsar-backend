package com.pulsar.kernel.text;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registry of every {@link TextProvider} bean on the classpath, keyed by id.
 * Mirrors {@link com.pulsar.kernel.voice.VoiceProviderRegistry}.
 *
 * <p>Per-tenant active provider is read from {@code voice_provider_config} —
 * yes, the same table as voice. The reasoning: in the common case (RingCentral
 * Advanced) the same OAuth credentials cover both voice and SMS, so storing
 * them once is the right shape. A future "split" tenant (voice on RC, SMS on
 * Twilio) would add a second row distinguished by provider_id.
 */
@Component
public class TextProviderRegistry {

    private final Map<String, TextProvider> byId;
    private final Map<String, TextWebhookAdapter> webhookById;
    private final Map<String, TextSender> senderById;

    public TextProviderRegistry(
        List<TextProvider> providers,
        List<TextWebhookAdapter> webhookAdapters,
        List<TextSender> senders
    ) {
        this.byId = providers.stream()
            .collect(Collectors.toUnmodifiableMap(TextProvider::id, p -> p));
        this.webhookById = webhookAdapters.stream()
            .collect(Collectors.toUnmodifiableMap(TextWebhookAdapter::id, a -> a));
        this.senderById = senders.stream()
            .collect(Collectors.toUnmodifiableMap(TextSender::id, s -> s));
    }

    public List<TextProvider> all() { return List.copyOf(byId.values()); }

    public TextProvider get(String id) {
        TextProvider p = byId.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown text provider: " + id);
        return p;
    }

    public Optional<TextWebhookAdapter> webhook(String id) {
        return Optional.ofNullable(webhookById.get(id));
    }

    public Optional<TextSender> sender(String id) {
        return Optional.ofNullable(senderById.get(id));
    }

    /** Pick the configured provider for the current tenant; falls back to the
     *  only one installed if not explicitly configured. */
    public TextProvider forCurrentTenant(TenantDataSources tenantDs) {
        var t = TenantContext.require();
        DataSource ds = tenantDs.forDb(t.dbName());
        try {
            var rows = new JdbcTemplate(ds).queryForList(
                "SELECT provider_id FROM voice_provider_config LIMIT 1"
            );
            if (!rows.isEmpty()) {
                String id = (String) rows.get(0).get("provider_id");
                if (id != null && byId.containsKey(id)) return byId.get(id);
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Table missing — provider module not installed.
        }
        if (byId.size() == 1) return byId.values().iterator().next();
        throw new IllegalStateException(
            "No text provider configured for tenant " + t.slug() + " (installed: " + byId.keySet() + ")"
        );
    }
}
