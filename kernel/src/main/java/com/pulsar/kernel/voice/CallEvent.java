package com.pulsar.kernel.voice;

import java.time.Instant;

/**
 * Provider-neutral call event normalized from any voice provider's webhook /
 * realtime stream. Voice consumer modules (caller-match, call-intel, copilot)
 * see only this shape — they never deal with RingCentral, Twilio, Zoom, etc.
 * specifics. The provider id stays on the event so consumers can correlate
 * back to provider-specific data when they need to (recording fetch, etc.).
 */
public record CallEvent(
    EventType eventType,
    String providerId,
    String sessionId,
    Direction direction,
    String fromPhone,
    String toPhone,
    String callerName,
    String recordingRef,
    Integer durationSec,
    Instant startedAt,
    Instant endedAt
) {
    public enum EventType { RINGING, CONNECTED, ENDED }
    public enum Direction { INBOUND, OUTBOUND, UNKNOWN }
}
