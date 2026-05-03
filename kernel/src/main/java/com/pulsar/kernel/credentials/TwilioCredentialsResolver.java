package com.pulsar.kernel.credentials;

import org.springframework.stereotype.Service;

/**
 * Centralized resolver for Twilio messaging credentials (account SID + auth
 * token + from-number) stored in {@code tenant_credentials}. Replaces the
 * pre-Phase-2 reads from {@code opendental_calendar_sms_config}.
 *
 * <p>The from-number is technically a tenant-level setting more than a
 * credential, but it travels with the SID/token in practice (a Twilio number
 * is bound to an account) and the calendar Settings UI always asks for all
 * three together — so we keep them co-located here.
 */
@Service
public class TwilioCredentialsResolver {

    public static final String PROVIDER = "twilio";
    public static final String KEY_ACCOUNT_SID = "account_sid";
    public static final String KEY_AUTH_TOKEN  = "auth_token";
    public static final String KEY_FROM_NUMBER = "from_number";

    public record Credentials(String accountSid, String authToken, String fromNumber) {
        public boolean isComplete() {
            return accountSid != null && !accountSid.isBlank()
                && authToken  != null && !authToken.isBlank()
                && fromNumber != null && !fromNumber.isBlank();
        }
    }

    public record Status(boolean hasAccountSid, boolean hasAuthToken, boolean hasFromNumber) {
        public boolean isComplete() { return hasAccountSid && hasAuthToken && hasFromNumber; }
    }

    private final CredentialsService creds;

    public TwilioCredentialsResolver(CredentialsService creds) {
        this.creds = creds;
    }

    public Credentials resolveForDb(String dbName) {
        return new Credentials(
            creds.resolve(dbName, PROVIDER, KEY_ACCOUNT_SID).value(),
            creds.resolve(dbName, PROVIDER, KEY_AUTH_TOKEN).value(),
            creds.resolve(dbName, PROVIDER, KEY_FROM_NUMBER).value()
        );
    }

    public Status statusForDb(String dbName) {
        return new Status(
            creds.status(dbName, PROVIDER, KEY_ACCOUNT_SID).hasTenantValue(),
            creds.status(dbName, PROVIDER, KEY_AUTH_TOKEN).hasTenantValue(),
            creds.status(dbName, PROVIDER, KEY_FROM_NUMBER).hasTenantValue()
        );
    }

    /**
     * Update — pass null to leave a field untouched. The auth_token field
     * is treated as "leave untouched if blank" specifically (admins re-open
     * Settings to rotate the SID + from-number without re-typing the token).
     */
    public void update(
        String dbName,
        String accountSid,
        String authToken,
        String fromNumber,
        String actorEmail,
        String actorRole
    ) {
        if (accountSid != null) writeOrClear(dbName, KEY_ACCOUNT_SID, accountSid, actorEmail, actorRole);
        if (fromNumber != null) writeOrClear(dbName, KEY_FROM_NUMBER, fromNumber, actorEmail, actorRole);
        // Auth token: only write when caller passes a non-blank value. Empty
        // string from the UI means "no change" (matches the existing
        // OpendentalCalendarController behavior the wizard relied on).
        if (authToken != null && !authToken.isBlank()) {
            creds.set(dbName, PROVIDER, KEY_AUTH_TOKEN, authToken, actorEmail, actorRole);
        }
    }

    private void writeOrClear(String dbName, String keyName, String value, String email, String role) {
        if (value.isBlank()) creds.clear(dbName, PROVIDER, keyName, email, role);
        else                 creds.set  (dbName, PROVIDER, keyName, value, email, role);
    }
}
