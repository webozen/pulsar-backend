package com.pulsar.kernel.credentials;

import org.springframework.stereotype.Service;

/**
 * Centralized resolver for Zoom Phone credentials (account ID + client ID +
 * client secret + from-number) stored in {@code tenant_credentials}.
 *
 * <p>The client_secret field is treated as "leave untouched if blank" — the
 * same pattern as Twilio's auth_token — so admins can rotate the account ID
 * or from-number without re-entering the secret.
 */
@Service
public class ZoomPhoneCredentialsResolver {

    public static final String PROVIDER          = "zoom-phone";
    public static final String KEY_ACCOUNT_ID    = "account_id";
    public static final String KEY_CLIENT_ID     = "client_id";
    public static final String KEY_CLIENT_SECRET = "client_secret";
    public static final String KEY_FROM_NUMBER   = "from_number";

    public record Credentials(String accountId, String clientId, String clientSecret, String fromNumber) {
        public boolean isComplete() {
            return accountId     != null && !accountId.isBlank()
                && clientId      != null && !clientId.isBlank()
                && clientSecret  != null && !clientSecret.isBlank()
                && fromNumber    != null && !fromNumber.isBlank();
        }
    }

    public record Status(boolean hasAccountId, boolean hasClientId, boolean hasClientSecret, boolean hasFromNumber) {
        public boolean isComplete() {
            return hasAccountId && hasClientId && hasClientSecret && hasFromNumber;
        }
    }

    private final CredentialsService creds;

    public ZoomPhoneCredentialsResolver(CredentialsService creds) {
        this.creds = creds;
    }

    public Credentials resolveForDb(String dbName) {
        return new Credentials(
            creds.resolve(dbName, PROVIDER, KEY_ACCOUNT_ID).value(),
            creds.resolve(dbName, PROVIDER, KEY_CLIENT_ID).value(),
            creds.resolve(dbName, PROVIDER, KEY_CLIENT_SECRET).value(),
            creds.resolve(dbName, PROVIDER, KEY_FROM_NUMBER).value()
        );
    }

    public Status statusForDb(String dbName) {
        return new Status(
            creds.status(dbName, PROVIDER, KEY_ACCOUNT_ID).hasTenantValue(),
            creds.status(dbName, PROVIDER, KEY_CLIENT_ID).hasTenantValue(),
            creds.status(dbName, PROVIDER, KEY_CLIENT_SECRET).hasTenantValue(),
            creds.status(dbName, PROVIDER, KEY_FROM_NUMBER).hasTenantValue()
        );
    }

    /**
     * Update — pass null to leave a field untouched. The client_secret field
     * is treated as "leave untouched if blank" specifically (admins re-open
     * Settings to rotate account ID / from-number without re-typing the secret).
     */
    public void update(
        String dbName,
        String accountId,
        String clientId,
        String clientSecret,
        String fromNumber,
        String actorEmail,
        String actorRole
    ) {
        if (accountId  != null) writeOrClear(dbName, KEY_ACCOUNT_ID,  accountId,  actorEmail, actorRole);
        if (clientId   != null) writeOrClear(dbName, KEY_CLIENT_ID,   clientId,   actorEmail, actorRole);
        if (fromNumber != null) writeOrClear(dbName, KEY_FROM_NUMBER, fromNumber, actorEmail, actorRole);
        // Client secret: only write when caller passes a non-blank value. Empty
        // string from the UI means "no change".
        if (clientSecret != null && !clientSecret.isBlank()) {
            creds.set(dbName, PROVIDER, KEY_CLIENT_SECRET, clientSecret, actorEmail, actorRole);
        }
    }

    private void writeOrClear(String dbName, String keyName, String value, String email, String role) {
        if (value.isBlank()) creds.clear(dbName, PROVIDER, keyName, email, role);
        else                 creds.set  (dbName, PROVIDER, keyName, value, email, role);
    }

    /** Wipe all four Zoom Phone credentials in one call. Used by the admin
     *  "Clear" button — the per-field PUT path can't clear client_secret
     *  because blank means "leave untouched" there. */
    public void clearAll(String dbName, String actorEmail, String actorRole) {
        creds.clear(dbName, PROVIDER, KEY_ACCOUNT_ID,    actorEmail, actorRole);
        creds.clear(dbName, PROVIDER, KEY_CLIENT_ID,     actorEmail, actorRole);
        creds.clear(dbName, PROVIDER, KEY_CLIENT_SECRET, actorEmail, actorRole);
        creds.clear(dbName, PROVIDER, KEY_FROM_NUMBER,   actorEmail, actorRole);
    }
}
