package com.pulsar.kernel.credentials;

import org.springframework.stereotype.Service;

/**
 * Thin facade preserving the historical resolver API for the modules that read
 * a Gemini key (translate, opendental-ai, copilot, call-intel, text-intel,
 * text-copilot). Internals delegate to {@link CredentialsService} which is the
 * canonical store.
 *
 * <p>Lives in kernel so every module gets it without a cross-module dep on
 * translate (the file's historical home before centralization).
 */
@Service
public class GeminiKeyResolver {

    private static final String PROVIDER = "gemini";
    private static final String KEY_NAME = "api_key";

    public record Resolution(String apiKey, Source source) {}

    public enum Source { TENANT, PLATFORM_DEFAULT, NONE }

    public record TenantKeyStatus(boolean hasTenantKey, boolean useDefault) {}

    private final CredentialsService creds;

    public GeminiKeyResolver(CredentialsService creds) {
        this.creds = creds;
    }

    public Resolution resolveForDb(String dbName) {
        CredentialsService.Resolution r = creds.resolve(dbName, PROVIDER, KEY_NAME);
        Source src = switch (r.source()) {
            case TENANT -> Source.TENANT;
            case PLATFORM_DEFAULT -> Source.PLATFORM_DEFAULT;
            case NONE -> Source.NONE;
        };
        return new Resolution(r.value(), src);
    }

    public boolean platformDefaultAvailable() {
        return creds.platformDefaultAvailable(PROVIDER);
    }

    public TenantKeyStatus statusForDb(String dbName) {
        CredentialsService.Status s = creds.status(dbName, PROVIDER, KEY_NAME);
        return new TenantKeyStatus(s.hasTenantValue(), s.useDefault());
    }

    /**
     * Admin/tenant write path. {@code apiKey == null} → leave untouched; "" → clear;
     * any value → set. Same convention for {@code useDefault}. The 3-arg overload
     * defaults audit fields to {@code (null, "super_admin")} for back-compat with
     * AdminApiKeysController (Admin principal carries no email today).
     */
    public void updateForDb(String dbName, String apiKey, Boolean useDefault) {
        updateForDb(dbName, apiKey, useDefault, null, "super_admin");
    }

    public void updateForDb(String dbName, String apiKey, Boolean useDefault, String actorEmail, String actorRole) {
        if (apiKey != null) {
            if (apiKey.isBlank()) {
                creds.clear(dbName, PROVIDER, KEY_NAME, actorEmail, actorRole);
            } else {
                creds.set(dbName, PROVIDER, KEY_NAME, apiKey, actorEmail, actorRole);
            }
        }
        if (useDefault != null) {
            creds.setUseDefault(dbName, PROVIDER, KEY_NAME, useDefault, actorEmail, actorRole);
        }
    }
}
