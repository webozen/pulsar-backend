package com.pulsar.voice.ringcentral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.text.TextEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class RingCentralTextWebhookAdapterTest {

    private final RingCentralTextWebhookAdapter adapter = new RingCentralTextWebhookAdapter();

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Wraps a body map inside the standard RC envelope. */
    private Map<String, Object> wrap(Map<String, Object> body) {
        return Map.of("body", body);
    }

    /** Builds a minimal inbound SMS body. */
    private Map<String, Object> smsBody(String id, String direction, String from,
                                        String to, String subject, String conversationId,
                                        String messageStatus) {
        Map<String, Object> b = new HashMap<>();
        b.put("id", id);
        b.put("type", "SMS");
        b.put("direction", direction);
        b.put("from", from == null ? Map.of() : Map.of("phoneNumber", from));
        b.put("to", to == null ? List.of() : List.of(Map.of("phoneNumber", to)));
        b.put("subject", subject);
        if (conversationId != null) b.put("conversation", Map.of("id", conversationId));
        if (messageStatus != null) b.put("messageStatus", messageStatus);
        return b;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — happy paths
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void parse_inbound_sms_with_conversation_id_as_thread_key() {
        Map<String, Object> body = smsBody("msg-001", "Inbound",
            "+15550001111", "+15552223333", "Hello!", "conv-abc", null);
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        TextEvent ev = result.get();
        assertThat(ev.eventType()).isEqualTo(TextEvent.EventType.RECEIVED);
        assertThat(ev.direction()).isEqualTo(TextEvent.Direction.INBOUND);
        assertThat(ev.providerId()).isEqualTo("ringcentral");
        assertThat(ev.providerMessageId()).isEqualTo("msg-001");
        assertThat(ev.threadKey()).isEqualTo("conv-abc");
        assertThat(ev.fromPhone()).isEqualTo("+15550001111");
        assertThat(ev.toPhone()).isEqualTo("+15552223333");
        assertThat(ev.body()).isEqualTo("Hello!");
        assertThat(ev.mediaUrls()).isEmpty();
    }

    @Test
    void parse_outbound_sms_fallback_thread_key_from_to_pair() {
        // No conversation.id — thread key should be "toPhone::fromPhone" for outbound
        Map<String, Object> body = smsBody("msg-002", "Outbound",
            "+15552223333", "+15550001111", "Reply here", null, "Sent");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        TextEvent ev = result.get();
        assertThat(ev.direction()).isEqualTo(TextEvent.Direction.OUTBOUND);
        assertThat(ev.eventType()).isEqualTo(TextEvent.EventType.SENT);
        // outbound: a = toPhone, b = fromPhone
        assertThat(ev.threadKey()).isEqualTo("+15550001111::+15552223333");
    }

    @Test
    void parse_inbound_sms_fallback_thread_key_from_to_pair() {
        // No conversation.id — inbound: a = fromPhone, b = toPhone
        Map<String, Object> body = smsBody("msg-003", "Inbound",
            "+15550001111", "+15552223333", "Hi", null, null);
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().threadKey()).isEqualTo("+15550001111::+15552223333");
    }

    @Test
    void parse_message_status_delivered_maps_to_delivered() {
        Map<String, Object> body = smsBody("msg-004", "Outbound",
            "+15551112222", "+15553334444", "Delivered msg", "conv-d", "Delivered");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().eventType()).isEqualTo(TextEvent.EventType.DELIVERED);
    }

    @Test
    void parse_message_status_delivery_failed_maps_to_failed() {
        Map<String, Object> body = smsBody("msg-005", "Outbound",
            "+15551112222", "+15553334444", "Failed msg", "conv-e", "DeliveryFailed");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().eventType()).isEqualTo(TextEvent.EventType.FAILED);
    }

    @Test
    void parse_message_status_sending_failed_maps_to_failed() {
        Map<String, Object> body = smsBody("msg-006", "Outbound",
            "+15551112222", "+15553334444", "Send error", "conv-f", "SendingFailed");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().eventType()).isEqualTo(TextEvent.EventType.FAILED);
    }

    @Test
    void parse_attachments_populate_media_urls() {
        Map<String, Object> body = smsBody("msg-007", "Inbound",
            "+15550001111", "+15552223333", null, "conv-g", null);
        body = new HashMap<>(body);
        body.put("attachments", List.of(
            Map.of("uri", "https://media.rc.com/a1.jpg"),
            Map.of("uri", "https://media.rc.com/a2.png")
        ));
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().mediaUrls())
            .containsExactly("https://media.rc.com/a1.jpg", "https://media.rc.com/a2.png");
    }

    @Test
    void parse_payload_without_body_key_falls_back_to_payload_itself() {
        // RC occasionally delivers the body fields at the top level (no "body" wrapper)
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "msg-008");
        payload.put("type", "SMS");
        payload.put("direction", "Inbound");
        payload.put("from", Map.of("phoneNumber", "+15550001111"));
        payload.put("to", List.of(Map.of("phoneNumber", "+15552223333")));
        payload.put("subject", "Top-level");
        payload.put("conversation", Map.of("id", "conv-h"));

        Optional<TextEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().providerMessageId()).isEqualTo("msg-008");
        assertThat(result.get().body()).isEqualTo("Top-level");
    }

    @Test
    void parse_text_type_accepted() {
        Map<String, Object> body = smsBody("msg-009", "Inbound",
            "+15550001111", "+15552223333", "Text type", "conv-i", null);
        body = new HashMap<>(body);
        body.put("type", "Text");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isPresent();
    }

    @Test
    void parse_pager_type_accepted() {
        Map<String, Object> body = smsBody("msg-010", "Inbound",
            "+15550001111", "+15552223333", "Pager msg", "conv-j", null);
        body = new HashMap<>(body);
        body.put("type", "Pager");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isPresent();
    }

    @Test
    void parse_instant_message_type_accepted() {
        Map<String, Object> body = smsBody("msg-011", "Inbound",
            "+15550001111", "+15552223333", "IM msg", "conv-k", null);
        body = new HashMap<>(body);
        body.put("type", "InstantMessage");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — empty returns
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void parse_non_sms_type_voicemail_returns_empty() {
        Map<String, Object> body = smsBody("msg-012", "Inbound",
            "+15550001111", "+15552223333", "VM body", "conv-l", null);
        body = new HashMap<>(body);
        body.put("type", "VoiceMail");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void parse_non_sms_type_fax_returns_empty() {
        Map<String, Object> body = smsBody("msg-013", "Inbound",
            "+15550001111", "+15552223333", "Fax body", "conv-m", null);
        body = new HashMap<>(body);
        body.put("type", "Fax");
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void parse_missing_id_returns_empty() {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "SMS");
        body.put("direction", "Inbound");
        body.put("from", Map.of("phoneNumber", "+15550001111"));
        body.put("to", List.of(Map.of("phoneNumber", "+15552223333")));
        // id deliberately omitted
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void parse_null_type_treated_as_sms_and_accepted() {
        // type == null is explicitly allowed by the type-guard condition
        Map<String, Object> body = new HashMap<>();
        body.put("id", "msg-014");
        body.put("direction", "Inbound");
        body.put("from", Map.of("phoneNumber", "+15550001111"));
        body.put("to", List.of(Map.of("phoneNumber", "+15552223333")));
        body.put("conversation", Map.of("id", "conv-n"));
        Optional<TextEvent> result = adapter.parse(wrap(body), Map.of());
        assertThat(result).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // validateSignature()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void validate_no_rows_in_db_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of()))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("verification-token", "any-token"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_blank_db_secret_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", ""))))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("verification-token", "some-token"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_matching_token_lowercase_header_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "secret-abc"))))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("verification-token", "secret-abc"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_matching_token_mixed_case_header_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "secret-xyz"))))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("Verification-Token", "secret-xyz"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_mismatched_token_returns_false() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "correct-secret"))))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("verification-token", "wrong-secret"),
                mock(DataSource.class));

            assertThat(result).isFalse();
        }
    }

    @Test
    void validate_null_db_secret_returns_true() {
        // Row exists but the secret column value is null — blank/null check treats as unset
        Map<String, Object> rowWithNull = new HashMap<>();
        rowWithNull.put("webhook_secret", null);
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(rowWithNull)))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("verification-token", "any-token"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }
}
