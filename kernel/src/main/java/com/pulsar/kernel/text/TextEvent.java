package com.pulsar.kernel.text;

import java.time.Instant;
import java.util.List;

/**
 * Provider-neutral inbound/outbound SMS-or-equivalent message normalized from
 * any text provider's webhook (RingCentral SMS, Twilio SMS, Telnyx, …). Voice
 * consumer modules already have {@code CallEvent}; this is the analog for
 * the text track.
 */
public record TextEvent(
    EventType eventType,
    String providerId,
    String providerMessageId,
    String threadKey,        // stable key correlating messages in the same conversation
    Direction direction,
    String fromPhone,
    String toPhone,
    String body,
    List<String> mediaUrls,  // MMS attachments
    Instant sentAt
) {
    public enum EventType { RECEIVED, SENT, DELIVERED, FAILED }
    public enum Direction { INBOUND, OUTBOUND }
}
