package com.pulsar.kernel.text;

import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Translates a single text provider's inbound webhook payload into a
 * {@link TextEvent}. One implementation per provider; Spring auto-collects
 * into {@code Map<String, TextWebhookAdapter>} keyed by id so controllers
 * dispatch by URL path variable.
 */
public interface TextWebhookAdapter {

    String id();

    Optional<TextEvent> parse(Map<String, Object> payload, Map<String, String> headers);

    boolean validateSignature(Map<String, Object> payload, Map<String, String> headers, DataSource tenantDs);
}
