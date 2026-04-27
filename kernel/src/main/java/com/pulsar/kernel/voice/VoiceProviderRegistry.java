package com.pulsar.kernel.voice;

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
 * Spring registry of every {@link VoiceProvider} bean on the classpath, keyed
 * by id. Resolves the active provider per tenant by reading
 * {@code voice_provider_config.provider_id} from the tenant DB; falls back to
 * the only registered provider when exactly one is installed (the common case
 * while RingCentral is the sole adapter).
 *
 * <p>This is the single point where provider selection happens. Voice
 * consumer modules call {@link #forCurrentTenant(TenantDataSources)} and never
 * import any specific provider class.
 */
@Component
public class VoiceProviderRegistry {

    private final Map<String, VoiceProvider> byId;
    private final Map<String, VoiceWebhookAdapter> webhookById;
    private final Map<String, RecordingFetcher> fetcherById;

    public VoiceProviderRegistry(
        List<VoiceProvider> providers,
        List<VoiceWebhookAdapter> webhookAdapters,
        List<RecordingFetcher> recordingFetchers
    ) {
        this.byId = providers.stream()
            .collect(Collectors.toUnmodifiableMap(VoiceProvider::id, p -> p));
        this.webhookById = webhookAdapters.stream()
            .collect(Collectors.toUnmodifiableMap(VoiceWebhookAdapter::id, a -> a));
        this.fetcherById = recordingFetchers.stream()
            .collect(Collectors.toUnmodifiableMap(RecordingFetcher::id, f -> f));
    }

    /** Look up the webhook parser for a provider. */
    public Optional<VoiceWebhookAdapter> webhook(String providerId) {
        return Optional.ofNullable(webhookById.get(providerId));
    }

    /** Look up the recording downloader for a provider. */
    public Optional<RecordingFetcher> fetcher(String providerId) {
        return Optional.ofNullable(fetcherById.get(providerId));
    }

    public List<VoiceProvider> all() { return List.copyOf(byId.values()); }

    public VoiceProvider get(String id) {
        VoiceProvider p = byId.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown voice provider: " + id);
        return p;
    }

    public boolean exists(String id) { return byId.containsKey(id); }

    /**
     * Pick the provider configured for the current tenant. If the tenant
     * hasn't picked one and only one provider is installed, return it. If
     * none is configured and multiple exist, throws — admin must configure.
     */
    public VoiceProvider forCurrentTenant(TenantDataSources tenantDs) {
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
            // Table may not exist yet (no provider module installed for this tenant).
        }
        if (byId.size() == 1) return byId.values().iterator().next();
        throw new IllegalStateException(
            "No voice provider configured for tenant " + t.slug() + " (installed: " + byId.keySet() + ")"
        );
    }
}
