package com.pulsar.kernel.text;

import java.io.IOException;
import java.util.List;
import javax.sql.DataSource;

/**
 * Outbound message sender. One implementation per provider. The actual
 * REST/SMPP/whatever call lives here; consumer modules just call
 * {@code sender.send(SendRequest)} and get back a provider-issued message id.
 */
public interface TextSender {

    String id();

    record SendRequest(String fromPhone, String toPhone, String body, List<String> mediaUrls) {}
    record SendResult(String providerMessageId, String status) {}

    SendResult send(SendRequest req, DataSource tenantDs) throws IOException;
}
