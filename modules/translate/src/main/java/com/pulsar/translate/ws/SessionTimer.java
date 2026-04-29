package com.pulsar.translate.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Per-WebSocket-session lifecycle timer. Schedules T-2min and T-1min warning
 * frames, fires a timeout-with-grace at the end of the session window, and
 * supports an "extend" operation that resets the clock without disturbing
 * the underlying Gemini stream — so the user sees a continuous conversation
 * while the platform records each extension as a separate session for
 * metering purposes.
 *
 * Threading: all callbacks fire on the shared ScheduledExecutorService thread
 * pool. Callers MUST handle thread-safety in their callbacks (e.g. WS sends
 * are inherently async; safeSend() in the handler is already synchronized).
 */
class SessionTimer {

    private static final Logger log = LoggerFactory.getLogger(SessionTimer.class);
    private static final long GRACE_MS = 30_000;
    private static final long WARNING_2_MS = 120_000;
    private static final long WARNING_1_MS = 60_000;

    private final String sessionId;
    private final int maxExtends;
    private final int extendGrantMin;
    private final ScheduledExecutorService scheduler;
    private final BiConsumer<String, Map<String, Object>> sendFrame;
    private final Consumer<String> onTimeout;

    private final AtomicInteger extendsUsed = new AtomicInteger(0);
    private volatile ScheduledFuture<?> warning2;
    private volatile ScheduledFuture<?> warning1;
    private volatile ScheduledFuture<?> timeout;
    private volatile boolean graceStarted = false;

    SessionTimer(
        String sessionId,
        int sessionDurationMin,
        int extendGrantMin,
        int maxExtends,
        ScheduledExecutorService scheduler,
        BiConsumer<String, Map<String, Object>> sendFrame,
        Consumer<String> onTimeout
    ) {
        this.sessionId = sessionId;
        this.extendGrantMin = extendGrantMin;
        this.maxExtends = maxExtends;
        this.scheduler = scheduler;
        this.sendFrame = sendFrame;
        this.onTimeout = onTimeout;
        scheduleAll(sessionDurationMin * 60_000L);
    }

    private void scheduleAll(long durationMs) {
        cancelAll();
        long warn2Delay = durationMs - WARNING_2_MS;
        long warn1Delay = durationMs - WARNING_1_MS;
        long timeoutDelay = durationMs;

        if (warn2Delay > 0) {
            warning2 = scheduler.schedule(() -> emitWarning(120), warn2Delay, TimeUnit.MILLISECONDS);
        }
        if (warn1Delay > 0) {
            warning1 = scheduler.schedule(() -> emitWarning(60), warn1Delay, TimeUnit.MILLISECONDS);
        }
        timeout = scheduler.schedule(this::onWindowEnd, timeoutDelay, TimeUnit.MILLISECONDS);
    }

    private void emitWarning(int secondsRemaining) {
        sendFrame.accept(sessionId, Map.of(
            "type", "session-warning",
            "secondsRemaining", secondsRemaining,
            "extendsRemaining", maxExtends - extendsUsed.get()
        ));
    }

    private void onWindowEnd() {
        // T+0: announce timeout-with-grace so the client can show the final
        // banner + Extend button. After GRACE_MS we hard-close.
        graceStarted = true;
        sendFrame.accept(sessionId, Map.of(
            "type", "session-timeout",
            "graceSeconds", GRACE_MS / 1000,
            "extendsRemaining", maxExtends - extendsUsed.get()
        ));
        timeout = scheduler.schedule(() -> {
            log.info("Session timer fired hard close: session={}", sessionId);
            onTimeout.accept(sessionId);
        }, GRACE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns true if the extend was accepted. Conversation history is preserved
     * (the underlying Gemini stream is untouched); the platform counter is the
     * only thing that increments.
     */
    boolean extend() {
        int used = extendsUsed.get();
        if (used >= maxExtends) {
            sendFrame.accept(sessionId, Map.of(
                "type", "extend-rejected",
                "reason", "max_extends_reached",
                "maxExtends", maxExtends
            ));
            return false;
        }
        extendsUsed.incrementAndGet();
        long newDurationMs = extendGrantMin * 60_000L;
        graceStarted = false;
        scheduleAll(newDurationMs);
        sendFrame.accept(sessionId, Map.of(
            "type", "session-extended",
            "newDurationMs", newDurationMs,
            "extendsUsed", extendsUsed.get(),
            "extendsRemaining", maxExtends - extendsUsed.get()
        ));
        log.info("Session extended: session={} extendsUsed={}/{}", sessionId, extendsUsed.get(), maxExtends);
        return true;
    }

    int extendsUsed() {
        return extendsUsed.get();
    }

    boolean isInGrace() {
        return graceStarted;
    }

    void cancel() {
        cancelAll();
    }

    private void cancelAll() {
        if (warning2 != null) warning2.cancel(false);
        if (warning1 != null) warning1.cancel(false);
        if (timeout != null) timeout.cancel(false);
    }
}
