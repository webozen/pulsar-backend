package com.pulsar.voice.ringcentral;

import com.pulsar.kernel.voice.CallEvent;
import com.pulsar.kernel.voice.VoiceWebhookAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Translates RingCentral's {@code /restapi/v1.0/subscription} delivery
 * payload into a {@link CallEvent}. RC delivers two relevant shapes:
 *
 * <ul>
 *   <li><b>SessionCompleted</b>: terminal event for a call. Contains
 *       parties[], status (Disconnected), recordings[], creationTime.</li>
 *   <li><b>NotifyEvent / TelephonySession</b>: state transitions
 *       (Setup → Answered → Disconnected). Useful for live signalling
 *       (Caller Match's screen-pop), but for post-call AI we mostly look at
 *       SessionCompleted.</li>
 * </ul>
 *
 * <p>Signature verification: RC supports a Verification-Token header signed
 * with the per-tenant {@code webhook_secret}. We compare in constant time;
 * a missing token on the SubscriptionRenewed handshake is allowed because
 * RC sends a Validation-Token instead — the controller, not the adapter,
 * handles that handshake.
 */
@Component
public class RingCentralWebhookAdapter implements VoiceWebhookAdapter {

    @Override public String id() { return "ringcentral"; }

    @Override
    public boolean validateSignature(Map<String, Object> payload, Map<String, String> headers, DataSource tenantDs) {
        String got = headerCi(headers, "Verification-Token");
        if (got == null || got.isBlank()) {
            // No signature yet — onboarding handshake (Validation-Token) is
            // handled at the controller layer, so we only fail closed when a
            // real event arrives without the token AND a secret is on file.
            String secret = readWebhookSecret(tenantDs);
            return secret == null || secret.isBlank();
        }
        String expected = readWebhookSecret(tenantDs);
        if (expected == null) return false;
        return constantTimeEquals(got, expected);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<CallEvent> parse(Map<String, Object> payload, Map<String, String> headers) {
        Object body = payload.get("body");
        if (!(body instanceof Map<?, ?> bodyMap)) return Optional.empty();
        Map<String, Object> session = mapOrNull(bodyMap.get("telephonySession"));
        if (session == null) session = (Map<String, Object>) bodyMap;
        if (session == null) return Optional.empty();

        String sessionId = (String) session.get("id");
        if (sessionId == null) sessionId = (String) session.get("sessionId");
        if (sessionId == null) return Optional.empty();

        Instant created = parseInstant((String) session.get("creationTime"));
        List<Map<String, Object>> parties = (List<Map<String, Object>>) session.get("parties");
        if (parties == null || parties.isEmpty()) return Optional.empty();
        Map<String, Object> party = parties.get(0);

        Map<String, Object> from = (Map<String, Object>) party.get("from");
        Map<String, Object> to   = (Map<String, Object>) party.get("to");
        String dirRaw = (String) party.get("direction");
        CallEvent.Direction direction = "Inbound".equalsIgnoreCase(dirRaw) ? CallEvent.Direction.INBOUND
            : "Outbound".equalsIgnoreCase(dirRaw) ? CallEvent.Direction.OUTBOUND
            : CallEvent.Direction.UNKNOWN;

        Map<String, Object> status = (Map<String, Object>) party.get("status");
        String code = status == null ? null : (String) status.get("code");
        CallEvent.EventType type = "Setup".equalsIgnoreCase(code) ? CallEvent.EventType.RINGING
            : "Answered".equalsIgnoreCase(code) ? CallEvent.EventType.CONNECTED
            : "Disconnected".equalsIgnoreCase(code) ? CallEvent.EventType.ENDED
            : CallEvent.EventType.RINGING;

        List<Map<String, Object>> recordings = (List<Map<String, Object>>) party.get("recordings");
        String recordingRef = null;
        if (recordings != null && !recordings.isEmpty()) {
            Object id = recordings.get(0).get("id");
            if (id != null) recordingRef = String.valueOf(id);
        }

        return Optional.of(new CallEvent(
            type, "ringcentral", sessionId, direction,
            from == null ? null : (String) from.get("phoneNumber"),
            to   == null ? null : (String) to.get("phoneNumber"),
            party.get("name") == null ? null : String.valueOf(party.get("name")),
            recordingRef,
            null, // durationSec — RC doesn't include it directly on the session payload
            created,
            type == CallEvent.EventType.ENDED ? Instant.now() : null
        ));
    }

    private static String readWebhookSecret(DataSource ds) {
        var rows = new JdbcTemplate(ds).queryForList(
            "SELECT webhook_secret FROM voice_provider_config WHERE provider_id = 'ringcentral'"
        );
        return rows.isEmpty() ? null : (String) rows.get(0).get("webhook_secret");
    }

    private static String headerCi(Map<String, String> h, String name) {
        if (h == null) return null;
        for (var e : h.entrySet()) if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) return null;
        try { return Instant.parse(iso); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }
}
