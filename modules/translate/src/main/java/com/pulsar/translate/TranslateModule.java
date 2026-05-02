package com.pulsar.translate;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class TranslateModule implements ModuleDefinition {

    @Override public String id() { return "translate"; }

    @Override public ModuleManifest manifest() {
        return new ModuleManifest("translate", "Translate", "Real-time AI translation", "🌐", "operations");
    }

    /**
     * Onboarded once a Gemini key is resolvable for this tenant — either a
     * tenant-set key in {@code tenant_credentials} or the platform-default
     * flag with a configured {@code PULSAR_DEFAULT_GEMINI_KEY}.
     *
     * <p>Pre-Phase-1 this checked for a row in {@code translate_config}; that
     * heuristic broke when {@code gemini_key} moved to the centralized
     * credential store and the wizard stopped writing this table.
     *
     * <p>We can't easily call the {@code GeminiKeyResolver} bean here because
     * {@link ModuleDefinition#isOnboarded} hands us a {@link DataSource}, not a
     * dbName. Reading the canonical store directly via the supplied DataSource
     * gives the same answer with one SQL round-trip and no extra wiring. The
     * {@code use_platform_default} branch checks only the flag — verifying the
     * env-var is set is the resolver's job at runtime; here we're just deciding
     * whether to bounce the user into the wizard.
     */
    @Override
    public boolean isOnboarded(DataSource tenantDs) {
        try {
            Integer n = new JdbcTemplate(tenantDs).queryForObject(
                "SELECT COUNT(*) FROM tenant_credentials "
                    + "WHERE provider = 'gemini' AND key_name = 'api_key' "
                    + "AND (LENGTH(value_ciphertext) > 0 OR use_platform_default = TRUE)",
                Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            // Table may be missing pre-kernel-migration on a stale tenant DB —
            // treat as not-onboarded so the user lands on the wizard.
            return false;
        }
    }
}
