package com.pulsar.kernel.credentials;

import org.springframework.stereotype.Service;

/**
 * Centralized resolver for OpenDental API keys (DeveloperKey + CustomerKey)
 * stored in {@code tenant_credentials}. Replaces the per-module reads from
 * {@code opendental_ai_config} and {@code opendental_calendar_config} that
 * existed pre-Phase-2.
 *
 * <p>No platform-default branch — DeveloperKey is per-vendor-app (Webozen's,
 * the same across tenants but not something we want to put in env to avoid
 * accidentally shipping it; CustomerKey is per-clinic, never shared).
 */
@Service
public class OpenDentalKeyResolver {

    public static final String PROVIDER = "opendental";
    public static final String KEY_DEVELOPER = "developer_key";
    public static final String KEY_CUSTOMER = "customer_key";

    public record Keys(String developerKey, String customerKey) {
        public boolean isComplete() {
            return developerKey != null && !developerKey.isBlank()
                && customerKey != null && !customerKey.isBlank();
        }
    }

    public record Status(boolean hasDeveloperKey, boolean hasCustomerKey) {
        public boolean isComplete() { return hasDeveloperKey && hasCustomerKey; }
    }

    private final CredentialsService creds;

    public OpenDentalKeyResolver(CredentialsService creds) {
        this.creds = creds;
    }

    public Keys resolveForDb(String dbName) {
        String dev = creds.resolve(dbName, PROVIDER, KEY_DEVELOPER).value();
        String cust = creds.resolve(dbName, PROVIDER, KEY_CUSTOMER).value();
        return new Keys(dev, cust);
    }

    public Status statusForDb(String dbName) {
        return new Status(
            creds.status(dbName, PROVIDER, KEY_DEVELOPER).hasTenantValue(),
            creds.status(dbName, PROVIDER, KEY_CUSTOMER).hasTenantValue()
        );
    }

    public void update(String dbName, String developerKey, String customerKey, String actorEmail, String actorRole) {
        if (developerKey != null) writeOrClear(dbName, KEY_DEVELOPER, developerKey, actorEmail, actorRole);
        if (customerKey != null)  writeOrClear(dbName, KEY_CUSTOMER,  customerKey,  actorEmail, actorRole);
    }

    private void writeOrClear(String dbName, String keyName, String value, String email, String role) {
        if (value.isBlank()) creds.clear(dbName, PROVIDER, keyName, email, role);
        else                 creds.set  (dbName, PROVIDER, keyName, value, email, role);
    }
}
