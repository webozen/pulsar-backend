package com.pulsar.voice.ringcentral;

import com.pulsar.kernel.text.TextEvent;
import com.pulsar.kernel.text.TextWebhookAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Translates RingCentral instant-message + SMS webhook envelopes
 * ({@code /restapi/v1.0/account/~/extension/~/message-store/instant?type=SMS})
 * into provider-neutral {@link TextEvent}s.
 *
 * <p>RC delivers SMS events under {@code body} with conversation, from,
 * to[], subject (the message body), attachments, creationTime, direction.
 * Outbound delivery receipts arrive on the same subscription with
 * {@code messageStatus} ∈ {Sent, Delivered, DeliveryFailed}.
 */
@Component
public class RingCentralTextWebhookAdapter implements TextWebhookAdapter {

    @Override public String id() { return "ringcentral"; }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<TextEvent> parse(Map<String, Object> payload, Map<String, String> headers) {
        Map<String, Object> body = (Map<String, Object>) payload.getOrDefault("body", payload);
        if (body == null) return Optional.empty();

        // Type guard — RC sends multiple event types on the same subscription.
        String type = strOrNull(body.get("type"));
        if (type != null && !"SMS".equalsIgnoreCase(type) && !"Text".equalsIgnoreCase(type)
                          && !"Pager".equalsIgnoreCase(type) && !"InstantMessage".equalsIgnoreCase(type)) {
            return Optional.empty();
        }

        String msgId = strOrNull(body.get("id"));
        if (msgId == null) return Optional.empty();

        String dirRaw = strOrNull(body.get("direction"));
        TextEvent.Direction direction = "Inbound".equalsIgnoreCase(dirRaw)
            ? TextEvent.Direction.INBOUND : TextEvent.Direction.OUTBOUND;

        Map<String, Object> from = (Map<String, Object>) body.getOrDefault("from", Map.of());
        String fromPhone = strOrNull(from.get("phoneNumber"));

        List<Map<String, Object>> toList = (List<Map<String, Object>>) body.getOrDefault("to", List.of());
        String toPhone = toList.isEmpty() ? null : strOrNull(toList.get(0).get("phoneNumber"));

        String text = strOrNull(body.get("subject"));

        List<String> media = new ArrayList<>();
        List<Map<String, Object>> atts = (List<Map<String, Object>>) body.getOrDefault("attachments", List.of());
        for (Map<String, Object> a : atts) {
            String uri = strOrNull(a.get("uri"));
            if (uri != null) media.add(uri);
        }

        // Thread key: RC's conversationId when available, else from↔to ordered pair.
        Map<String, Object> conv = (Map<String, Object>) body.getOrDefault("conversation", Map.of());
        String threadKey = strOrNull(conv.get("id"));
        if (threadKey == null) {
            String a = direction == TextEvent.Direction.INBOUND ? fromPhone : toPhone;
            String b = direction == TextEvent.Direction.INBOUND ? toPhone   : fromPhone;
            threadKey = (a == null ? "?" : a) + "::" + (b == null ? "?" : b);
        }

        TextEvent.EventType eventType = switch (Objects.requireNonNullElse(strOrNull(body.get("messageStatus")), "")) {
            case "Sent"           -> TextEvent.EventType.SENT;
            case "Delivered"      -> TextEvent.EventType.DELIVERED;
            case "DeliveryFailed", "SendingFailed" -> TextEvent.EventType.FAILED;
            default -> direction == TextEvent.Direction.INBOUND ? TextEvent.EventType.RECEIVED : TextEvent.EventType.SENT;
        };

        Instant sentAt = parseInstant(body.get("creationTime"));

        return Optional.of(new TextEvent(
            eventType, "ringcentral", msgId, threadKey,
            direction, fromPhone, toPhone, text, media, sentAt
        ));
    }

    @Override
    public boolean validateSignature(Map<String, Object> payload, Map<String, String> headers, DataSource tenantDs) {
        String received = headers.getOrDefault("verification-token", headers.get("Verification-Token"));
        try {
            var rows = new JdbcTemplate(tenantDs).queryForList(
                "SELECT webhook_secret FROM voice_provider_config WHERE provider_id = 'ringcentral'"
            );
            if (rows.isEmpty()) return true;
            String expected = (String) rows.get(0).get("webhook_secret");
            if (expected == null || expected.isBlank()) return true;
            return Objects.equals(expected, received);
        } catch (org.springframework.dao.DataAccessException e) {
            return true;
        }
    }

    private static String strOrNull(Object v) { return v == null ? null : v.toString(); }
    private static Instant parseInstant(Object v) {
        if (v == null) return null;
        try { return Instant.parse(v.toString()); } catch (Exception e) { return null; }
    }
}
