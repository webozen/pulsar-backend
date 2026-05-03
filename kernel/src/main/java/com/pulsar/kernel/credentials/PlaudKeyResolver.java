package com.pulsar.kernel.credentials;

import org.springframework.stereotype.Service;

/**
 * Centralized resolver for the Plaud bearer token stored in
 * {@code tenant_credentials}. Replaces the pre-Phase-2 read from
 * {@code ai_notes_config.plaud_token}.
 */
@Service
public class PlaudKeyResolver {

    public static final String PROVIDER = "plaud";
    public static final String KEY_BEARER_TOKEN = "bearer_token";

    public record TokenStatus(boolean hasToken) {}

    private final CredentialsService creds;

    public PlaudKeyResolver(CredentialsService creds) {
        this.creds = creds;
    }

    public String resolveForDb(String dbName) {
        return creds.resolve(dbName, PROVIDER, KEY_BEARER_TOKEN).value();
    }

    public TokenStatus statusForDb(String dbName) {
        return new TokenStatus(creds.status(dbName, PROVIDER, KEY_BEARER_TOKEN).hasTenantValue());
    }

    public void update(String dbName, String bearerToken, String actorEmail, String actorRole) {
        if (bearerToken == null) return;
        if (bearerToken.isBlank()) creds.clear(dbName, PROVIDER, KEY_BEARER_TOKEN, actorEmail, actorRole);
        else                       creds.set  (dbName, PROVIDER, KEY_BEARER_TOKEN, bearerToken, actorEmail, actorRole);
    }
}
