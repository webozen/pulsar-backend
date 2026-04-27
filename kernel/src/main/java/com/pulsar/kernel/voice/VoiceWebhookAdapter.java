package com.pulsar.kernel.voice;

import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Translates a single voice provider's inbound webhook payload into a
 * normalized {@link CallEvent}. One implementation per provider; Spring auto-
 * collects them into {@code Map<String, VoiceWebhookAdapter>} keyed by id so
 * controllers can dispatch by URL path variable.
 */
public interface VoiceWebhookAdapter {

    /** Stable provider id, must match {@link VoiceProvider#id()}. */
    String id();

    /**
     * Parse a payload + headers into a CallEvent. Returns empty for
     * non-call events (subscription validation, presence, SMS, …).
     */
    Optional<CallEvent> parse(Map<String, Object> payload, Map<String, String> headers);

    /**
     * Validate the payload's authenticity (HMAC, shared secret, etc.) against
     * per-tenant configuration. Returning {@code false} causes the controller
     * to reject the webhook with 401.
     */
    boolean validateSignature(Map<String, Object> payload, Map<String, String> headers, DataSource tenantDs);
}
