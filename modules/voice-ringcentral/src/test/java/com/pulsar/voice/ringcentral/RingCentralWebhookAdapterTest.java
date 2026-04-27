package com.pulsar.voice.ringcentral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.voice.CallEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class RingCentralWebhookAdapterTest {

    private final RingCentralWebhookAdapter adapter = new RingCentralWebhookAdapter();

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Builds a minimal telephonySession wrapped inside payload.body */
    private Map<String, Object> wrapSession(Map<String, Object> session) {
        return Map.of("body", Map.of("telephonySession", session));
    }

    /** Builds a minimal party map. */
    private Map<String, Object> party(String direction, String statusCode,
                                      String from, String to, String name) {
        Map<String, Object> p = new HashMap<>();
        p.put("direction", direction);
        p.put("status", statusCode == null ? null : Map.of("code", statusCode));
        p.put("from", from == null ? null : Map.of("phoneNumber", from));
        p.put("to",   to   == null ? null : Map.of("phoneNumber", to));
        p.put("name", name);
        return p;
    }

    /** Builds a minimal session map with one party and a known creationTime. */
    private Map<String, Object> session(String id, String creationTime,
                                        Map<String, Object> party) {
        Map<String, Object> s = new HashMap<>();
        s.put("id", id);
        s.put("creationTime", creationTime);
        s.put("parties", List.of(party));
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — happy paths
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void parse_inbound_setup_status_maps_to_ringing() {
        Map<String, Object> p = party("Inbound", "Setup", "+15550001111", "+15552223333", "Alice");
        Map<String, Object> payload = wrapSession(session("sess-001", "2024-01-15T10:00:00Z", p));

        Optional<CallEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        CallEvent ev = result.get();
        assertThat(ev.eventType()).isEqualTo(CallEvent.EventType.RINGING);
        assertThat(ev.direction()).isEqualTo(CallEvent.Direction.INBOUND);
        assertThat(ev.sessionId()).isEqualTo("sess-001");
        assertThat(ev.providerId()).isEqualTo("ringcentral");
        assertThat(ev.fromPhone()).isEqualTo("+15550001111");
        assertThat(ev.toPhone()).isEqualTo("+15552223333");
        assertThat(ev.callerName()).isEqualTo("Alice");
        assertThat(ev.recordingRef()).isNull();
        assertThat(ev.startedAt()).isEqualTo(Instant.parse("2024-01-15T10:00:00Z"));
        assertThat(ev.endedAt()).isNull();
    }

    @Test
    void parse_outbound_answered_status_maps_to_connected() {
        Map<String, Object> p = party("Outbound", "Answered", "+15550001111", "+15552223333", "Bob");
        Map<String, Object> payload = wrapSession(session("sess-002", "2024-01-15T11:00:00Z", p));

        Optional<CallEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        CallEvent ev = result.get();
        assertThat(ev.eventType()).isEqualTo(CallEvent.EventType.CONNECTED);
        assertThat(ev.direction()).isEqualTo(CallEvent.Direction.OUTBOUND);
        assertThat(ev.sessionId()).isEqualTo("sess-002");
        assertThat(ev.endedAt()).isNull();
    }

    @Test
    void parse_disconnected_status_maps_to_ended_and_populates_ended_at() {
        Map<String, Object> p = party("Inbound", "Disconnected", "+15550001111", "+15552223333", null);
        Map<String, Object> payload = wrapSession(session("sess-003", "2024-01-15T12:00:00Z", p));

        Instant before = Instant.now();
        Optional<CallEvent> result = adapter.parse(payload, Map.of());
        Instant after = Instant.now();

        assertThat(result).isPresent();
        CallEvent ev = result.get();
        assertThat(ev.eventType()).isEqualTo(CallEvent.EventType.ENDED);
        assertThat(ev.endedAt()).isNotNull().isBetween(before, after);
    }

    @Test
    void parse_disconnected_with_recording_populates_recording_ref() {
        Map<String, Object> p = party("Inbound", "Disconnected", "+15550001111", "+15552223333", null);
        p = new HashMap<>(p);
        p.put("recordings", List.of(Map.of("id", "rec-abc-123")));
        Map<String, Object> payload = wrapSession(session("sess-004", null, p));

        Optional<CallEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().recordingRef()).isEqualTo("rec-abc-123");
    }

    @Test
    void parse_fallback_body_without_telephony_session_wrapper() {
        // Body itself has id/parties (no telephonySession key)
        Map<String, Object> p = party("Inbound", "Setup", "+15550001111", "+15552223333", "Carol");
        Map<String, Object> flatBody = new HashMap<>();
        flatBody.put("id", "sess-005");
        flatBody.put("creationTime", "2024-02-01T09:30:00Z");
        flatBody.put("parties", List.of(p));
        Map<String, Object> payload = Map.of("body", flatBody);

        Optional<CallEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().sessionId()).isEqualTo("sess-005");
        assertThat(result.get().eventType()).isEqualTo(CallEvent.EventType.RINGING);
        assertThat(result.get().startedAt()).isEqualTo(Instant.parse("2024-02-01T09:30:00Z"));
    }

    @Test
    void parse_session_id_falls_back_to_session_id_field() {
        // Some RC events use "sessionId" instead of "id"
        Map<String, Object> p = party("Inbound", "Setup", "+15550001111", "+15552223333", null);
        Map<String, Object> session = new HashMap<>();
        session.put("sessionId", "fallback-sess-006");
        session.put("parties", List.of(p));
        Map<String, Object> payload = wrapSession(session);

        Optional<CallEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().sessionId()).isEqualTo("fallback-sess-006");
    }

    @Test
    void parse_creation_time_parsed_as_instant() {
        Map<String, Object> p = party("Outbound", "Answered", null, null, null);
        Map<String, Object> payload = wrapSession(session("sess-007", "2024-06-15T20:45:30.123Z", p));

        Optional<CallEvent> result = adapter.parse(payload, Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().startedAt()).isEqualTo(Instant.parse("2024-06-15T20:45:30.123Z"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parse() — empty returns
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void parse_body_not_a_map_returns_empty() {
        Map<String, Object> payload = Map.of("body", "not-a-map");
        assertThat(adapter.parse(payload, Map.of())).isEmpty();
    }

    @Test
    void parse_no_session_and_body_has_no_id_returns_empty() {
        // Body is a Map but has no "telephonySession" and no "id"/"sessionId"
        Map<String, Object> payload = Map.of("body", Map.of("someOtherField", "value"));
        assertThat(adapter.parse(payload, Map.of())).isEmpty();
    }

    @Test
    void parse_no_parties_returns_empty() {
        Map<String, Object> session = Map.of("id", "sess-empty", "parties", List.of());
        Map<String, Object> payload = wrapSession(session);
        assertThat(adapter.parse(payload, Map.of())).isEmpty();
    }

    @Test
    void parse_null_parties_returns_empty() {
        Map<String, Object> session = new HashMap<>();
        session.put("id", "sess-null-parties");
        session.put("parties", null);
        Map<String, Object> payload = wrapSession(session);
        assertThat(adapter.parse(payload, Map.of())).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // validateSignature()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void validate_blank_token_and_no_db_secret_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of()))) {

            boolean result = adapter.validateSignature(
                Map.of(), Map.of("Verification-Token", ""), mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_blank_token_with_db_secret_returns_false() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "tok-secret"))))) {

            boolean result = adapter.validateSignature(
                Map.of(), Map.of(), mock(DataSource.class));

            assertThat(result).isFalse();
        }
    }

    @Test
    void validate_matching_token_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "my-secret-token"))))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("Verification-Token", "my-secret-token"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_mismatched_token_returns_false() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "correct-token"))))) {

            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("Verification-Token", "wrong-token"),
                mock(DataSource.class));

            assertThat(result).isFalse();
        }
    }

    @Test
    void validate_header_name_matched_case_insensitively() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", "secret-xyz"))))) {

            // lowercase header key — should still be found
            boolean result = adapter.validateSignature(
                Map.of(),
                Map.of("verification-token", "secret-xyz"),
                mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }

    @Test
    void validate_absent_token_header_with_blank_db_secret_returns_true() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("webhook_secret", ""))))) {

            // No token in headers, DB secret is blank string → should return true
            boolean result = adapter.validateSignature(
                Map.of(), Map.of(), mock(DataSource.class));

            assertThat(result).isTrue();
        }
    }
}
