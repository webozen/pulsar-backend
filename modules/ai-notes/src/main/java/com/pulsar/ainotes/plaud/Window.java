// pulsar-backend/modules/ai-notes/src/main/java/com/pulsar/ainotes/plaud/Window.java
package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Duration;
import java.util.Optional;

public enum Window {
    H24("24h", Duration.ofHours(24)),
    D7("7d",   Duration.ofDays(7)),
    D30("30d",  Duration.ofDays(30)),
    ALL("all",  null);

    private final String value;
    private final Duration duration;

    Window(String value, Duration duration) {
        this.value = value;
        this.duration = duration;
    }

    public Optional<Duration> duration() { return Optional.ofNullable(duration); }

    @JsonCreator
    public static Window fromValue(String v) {
        for (Window w : values()) {
            if (w.value.equalsIgnoreCase(v)) return w;
        }
        throw new IllegalArgumentException("unknown window: " + v);
    }
}
