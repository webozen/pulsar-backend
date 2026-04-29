package com.pulsar.translate.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import okhttp3.*;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.*;

@Component
public class GeminiProxyHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GeminiProxyHandler.class);

    private static final String GEMINI_WS_BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final String GEMINI_MODEL =
        System.getenv().getOrDefault("GEMINI_LIVE_MODEL", "gemini-3.1-flash-live-preview");

    private final JwtService jwtService;
    private final TenantDataSources tenantDs;
    private final TenantRepository tenantRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient okClient = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout — streaming session
        .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // sessionId → OkHttp WebSocket to Gemini
    private final ConcurrentHashMap<String, okhttp3.WebSocket> geminiSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> authPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> authTimeouts = new ConcurrentHashMap<>();
    // True once Gemini's setupComplete has arrived for this session.
    private final ConcurrentHashMap<String, Boolean> geminiReady = new ConcurrentHashMap<>();
    // Frames that arrived between auth-success and setupComplete. Flushed once Gemini is ready.
    private final ConcurrentHashMap<String, Deque<String>> pendingFrames = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_FRAMES = 50;

    private static final long GEMINI_SEND_DEADLINE_MS = 5_000;

    // Per-type inbound frame caps. Audio frames are base64-encoded PCM @ 16kHz / 4096 samples
    // (~11 KB per chunk), so 400 KB is a generous cap that absorbs back-pressure spikes
    // without letting a malicious client spam memory through the 2 MB WS buffer.
    private static final int MAX_AUDIO_FRAME_BYTES = 400 * 1024;
    private static final int MAX_TEXT_FRAME_BYTES = 8 * 1024;
    private static final int MAX_CONTROL_FRAME_BYTES = 4 * 1024;

    public GeminiProxyHandler(JwtService jwtService, TenantDataSources tenantDs, TenantRepository tenantRepo) {
        this.jwtService = jwtService;
        this.tenantDs = tenantDs;
        this.tenantRepo = tenantRepo;
    }

    @Override
    public void handleMessage(org.springframework.web.socket.WebSocketSession session,
                              org.springframework.web.socket.WebSocketMessage<?> message) throws Exception {
        try {
            applyMdc(session);
            log.info("RAW WS message: session={} class={} payload-preview={}", session.getId(),
                message.getClass().getSimpleName(),
                message.getPayload().toString().length() > 50 ? message.getPayload().toString().substring(0, 50) : message.getPayload().toString());
            super.handleMessage(session, message);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            applyMdc(session);
            log.info("Browser WS connected: session={} uri={} remote={}", session.getId(), session.getUri(), session.getRemoteAddress());
            authPending.put(session.getId(), true);
            ScheduledFuture<?> timeout = scheduler.schedule(() -> {
                if (authPending.containsKey(session.getId())) {
                    safeSend(session, Map.of("type", "error", "message", "Auth timeout"));
                    closeSession(session, CloseStatus.POLICY_VIOLATION);
                }
            }, 10, TimeUnit.SECONDS);
            authTimeouts.put(session.getId(), timeout);
        } finally {
            MDC.clear();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            applyMdc(session);
            handleTextMessageInternal(session, message);
        } finally {
            MDC.clear();
        }
    }

    private void handleTextMessageInternal(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        int payloadBytes = payload.length();
        // Hard ceiling — anything above the largest per-type cap is rejected before we parse.
        if (payloadBytes > MAX_AUDIO_FRAME_BYTES) {
            log.warn("Frame too large: session={} bytes={} cap={}", session.getId(), payloadBytes, MAX_AUDIO_FRAME_BYTES);
            safeSend(session, Map.of("type", "error", "message", "Frame too large"));
            closeSession(session, new CloseStatus(1009, "MESSAGE_TOO_BIG"));
            return;
        }
        log.info("Browser msg: session={} type={}", session.getId(), payload.length() > 50 ? payload.substring(0, 50) : payload);
        Map<String, Object> msg = mapper.readValue(payload, new TypeReference<>() {});
        String type = (String) msg.get("type");
        // Per-type caps — reject before doing any processing.
        int typeCap = switch (type == null ? "" : type) {
            case "audio" -> MAX_AUDIO_FRAME_BYTES;
            case "text" -> MAX_TEXT_FRAME_BYTES;
            default -> MAX_CONTROL_FRAME_BYTES;
        };
        if (payloadBytes > typeCap) {
            log.warn("Frame exceeds per-type cap: session={} type={} bytes={} cap={}", session.getId(), type, payloadBytes, typeCap);
            safeSend(session, Map.of("type", "error", "message", "Frame exceeds " + type + " cap"));
            closeSession(session, new CloseStatus(1009, "MESSAGE_TOO_BIG"));
            return;
        }

        if (authPending.containsKey(session.getId())) {
            handleAuth(session, msg);
            return;
        }

        okhttp3.WebSocket geminiWs = geminiSockets.get(session.getId());
        if (geminiWs == null) {
            log.warn("No Gemini socket for session={} type={}", session.getId(), type);
            return;
        }

        if ("audio".equals(type)) {
            String data = (String) msg.get("data");
            if (data == null || data.isEmpty()) return;
            String json = mapper.writeValueAsString(Map.of(
                "realtimeInput", Map.of("audio", Map.of("data", data, "mimeType", "audio/pcm;rate=16000"))
            ));
            queueOrSend(session, geminiWs, json, "audio");
        } else if ("text".equals(type)) {
            String text = (String) msg.get("text");
            if (text == null || text.isBlank()) return;
            String json = mapper.writeValueAsString(Map.of(
                "clientContent", Map.of(
                    "turns", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", text)))),
                    "turnComplete", true)
            ));
            queueOrSend(session, geminiWs, json, "text");
        } else if ("end".equals(type)) {
            closeSession(session, CloseStatus.NORMAL);
        }
    }

    private void handleAuth(WebSocketSession session, Map<String, Object> msg) {
        if (!"auth".equals(msg.get("type"))) {
            safeSend(session, Map.of("type", "error", "message", "Expected auth message first"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        ScheduledFuture<?> timeout = authTimeouts.remove(session.getId());
        if (timeout != null) timeout.cancel(false);
        authPending.remove(session.getId());

        String token = (String) msg.get("token");
        if (token == null) {
            safeSend(session, Map.of("type", "error", "message", "Missing token"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        Claims claims;
        try { claims = jwtService.parse(token); }
        catch (Exception e) {
            safeSend(session, Map.of("type", "error", "message", "Invalid token"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        String slug = claims.get("slug", String.class);
        if (slug == null) {
            safeSend(session, Map.of("type", "error", "message", "Invalid token: missing slug"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("tenant_slug", slug);
        MDC.put("tenant_id", slug);

        var tenantOpt = tenantRepo.findBySlug(slug);
        if (tenantOpt.isEmpty()) {
            safeSend(session, Map.of("type", "error", "message", "Tenant not found"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        var tenant = tenantOpt.get();

        if (!tenant.activeModules().contains("translate")) {
            safeSend(session, Map.of("type", "error", "message", "module_not_active"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(tenantDs.forDb(tenant.dbName()));
        var rows = jdbc.queryForList("SELECT gemini_key FROM translate_config WHERE id = 1");
        if (rows.isEmpty()) {
            safeSend(session, Map.of("type", "error", "message", "Gemini key not configured — complete onboarding first"));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String geminiKey = (String) rows.get(0).get("gemini_key");

        String sourceLang = msg.getOrDefault("sourceLang", "auto").toString();
        String targetLang = msg.getOrDefault("targetLang", "en").toString();
        String mode       = msg.getOrDefault("mode", "conversation").toString();

        if (!SystemInstructions.VALID_MODES.contains(mode)) {
            safeSend(session, Map.of("type", "error", "message", "Invalid mode: " + mode));
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        String systemInstruction = SystemInstructions.build(mode, sourceLang, targetLang);
        openGeminiConnection(session, geminiKey, systemInstruction);
        log.info("Translate session: tenant={} mode={} src={} tgt={} model={}", slug, mode, sourceLang, targetLang, GEMINI_MODEL);
    }

    private void openGeminiConnection(WebSocketSession browserSession, String geminiKey, String systemInstruction) {
        String url = GEMINI_WS_BASE + "?key=" + geminiKey;

        Request request = new Request.Builder().url(url).build();

        // OkHttp dispatches listener callbacks on its own thread pool, which does NOT
        // inherit the MDC from the Spring WS handler thread. Snapshot the current MDC
        // and re-apply it at the top of each callback so log lines stay correlated.
        final Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        okClient.newWebSocket(request, new WebSocketListener() {

            private void applyCallbackMdc() {
                if (mdcSnapshot != null) {
                    MDC.setContextMap(mdcSnapshot);
                } else {
                    MDC.clear();
                }
            }

            @Override
            public void onOpen(okhttp3.WebSocket ws, Response response) {
                try {
                    applyCallbackMdc();
                    log.info("Gemini WS opened: session={} model={}", browserSession.getId(), GEMINI_MODEL);
                    geminiSockets.put(browserSession.getId(), ws);
                    geminiReady.put(browserSession.getId(), false);
                    pendingFrames.put(browserSession.getId(), new ConcurrentLinkedDeque<>());
                    try {
                        // Build setup message
                        Map<String, Object> genConfig = new LinkedHashMap<>();
                        genConfig.put("response_modalities", List.of("AUDIO"));
                        genConfig.put("speech_config", Map.of("voice_config", Map.of(
                            "prebuilt_voice_config", Map.of("voice_name", "Kore"))));

                        Map<String, Object> setupInner = new LinkedHashMap<>();
                        setupInner.put("model", "models/" + GEMINI_MODEL);
                        setupInner.put("generation_config", genConfig);
                        setupInner.put("input_audio_transcription", new LinkedHashMap<>());
                        setupInner.put("output_audio_transcription", new LinkedHashMap<>());
                        setupInner.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));

                        String setupJson = mapper.writeValueAsString(Map.of("setup", setupInner));
                        log.info("Sending setup to Gemini: {}", setupJson);
                        sendWithWatchdog(browserSession, ws, setupJson, "setup");
                    } catch (Exception e) {
                        log.error("Gemini setup failed: {}", e.getMessage());
                        safeSend(browserSession, Map.of("type", "error", "message", "Gemini setup failed: " + e.getMessage()));
                        closeSession(browserSession, CloseStatus.SERVER_ERROR);
                    }
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, String text) {
                try {
                    applyCallbackMdc();
                    log.info("Gemini text msg: session={} len={} preview={}", browserSession.getId(), text.length(),
                        text.length() > 200 ? text.substring(0, 200) : text);
                    handleGeminiMessage(browserSession, text);
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onMessage(okhttp3.WebSocket ws, ByteString bytes) {
                try {
                    applyCallbackMdc();
                    // Gemini sometimes sends binary; try parsing as text
                    String text = bytes.utf8();
                    log.info("Gemini binary msg session={} bytes={} preview={}", browserSession.getId(), bytes.size(),
                        text.length() > 100 ? text.substring(0, 100) : text);
                    handleGeminiMessage(browserSession, text);
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onClosing(okhttp3.WebSocket ws, int code, String reason) {
                try {
                    applyCallbackMdc();
                    log.info("Gemini closing: session={} code={} reason='{}'", browserSession.getId(), code, reason);
                    ws.close(code, reason);
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                try {
                    applyCallbackMdc();
                    log.info("Gemini closed: session={} code={} reason='{}'", browserSession.getId(), code, reason);
                    // Delay close so the error message reaches the browser before the WS connection drops
                    safeSend(browserSession, Map.of("type", "error", "message", "Gemini closed " + code + ": " + (reason.isEmpty() ? "connection ended" : reason)));
                    scheduler.schedule(() -> closeSession(browserSession, CloseStatus.NORMAL), 200, TimeUnit.MILLISECONDS);
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onFailure(okhttp3.WebSocket ws, Throwable t, Response response) {
                try {
                    applyCallbackMdc();
                    String msg = response != null ? response.code() + " " + response.message() : t.getMessage();
                    log.error("Gemini failure: session={} error={}", browserSession.getId(), msg);
                    safeSend(browserSession, Map.of("type", "error", "message", "Gemini connection failed: " + msg));
                    scheduler.schedule(() -> closeSession(browserSession, CloseStatus.SERVER_ERROR), 200, TimeUnit.MILLISECONDS);
                } finally {
                    MDC.clear();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleGeminiMessage(WebSocketSession session, String raw) {
        try {
            Map<String, Object> msg = mapper.readValue(raw, new TypeReference<>() {});

            if (msg.containsKey("setupComplete") || msg.containsKey("setup_complete")) {
                log.info("Gemini setupComplete for session={}", session.getId());
                geminiReady.put(session.getId(), true);
                flushPendingFrames(session);
                safeSend(session, Map.of("type", "ready"));
                return;
            }

            Map<String, Object> sc = (Map<String, Object>) msg.get("serverContent");
            if (sc == null) return;

            Map<String, Object> mt = (Map<String, Object>) sc.get("modelTurn");
            if (mt != null) {
                var parts = (List<Map<String, Object>>) mt.get("parts");
                if (parts != null) for (var p : parts) {
                    String text = (String) p.get("text");
                    if (text != null && !Boolean.TRUE.equals(p.get("thought"))) {
                        safeSend(session, Map.of("type", "translation", "text", text,
                            "final", Boolean.TRUE.equals(sc.get("turnComplete"))));
                    }
                    Map<String, Object> id = (Map<String, Object>) p.get("inlineData");
                    if (id != null) safeSend(session, Map.of("type", "audio", "data", id.get("data"), "mimeType", id.get("mimeType")));
                }
            }

            Map<String, Object> it = (Map<String, Object>) sc.get("inputTranscription");
            if (it != null && it.get("text") != null)
                safeSend(session, Map.of("type", "input-transcription", "text", it.get("text")));

            Map<String, Object> ot = (Map<String, Object>) sc.get("outputTranscription");
            if (ot != null && ot.get("text") != null)
                safeSend(session, Map.of("type", "output-transcription", "text", ot.get("text")));

            if (Boolean.TRUE.equals(sc.get("turnComplete")))
                safeSend(session, Map.of("type", "turn-complete"));

        } catch (Exception e) {
            log.error("Gemini parse error: session={} err={}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            applyMdc(session);
            log.info("Browser WS closed: session={} status={}", session.getId(), status);
            cleanup(session.getId());
        } finally {
            MDC.clear();
        }
    }

    /**
     * Populate MDC for the current thread from WS session state.
     * Tomcat WS dispatches each frame on a pooled thread, so MDC must be re-seeded
     * at the top of every handler entry point. Callers MUST MDC.clear() in finally.
     */
    private void applyMdc(WebSocketSession session) {
        Object slug = session.getAttributes().get("tenant_slug");
        if (slug != null) {
            MDC.put("tenant_id", slug.toString());
        }
        MDC.put("session_id", session.getId());
    }

    private void cleanup(String id) {
        authPending.remove(id);
        ScheduledFuture<?> t = authTimeouts.remove(id);
        if (t != null) t.cancel(false);
        geminiReady.remove(id);
        pendingFrames.remove(id);
        okhttp3.WebSocket g = geminiSockets.remove(id);
        if (g != null) { try { g.close(1000, "done"); } catch (Exception ignored) {} }
    }

    private void safeSend(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (session.isOpen()) synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception e) { log.debug("Send failed: {}", e.getMessage()); }
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
        try { if (session.isOpen()) session.close(status); } catch (Exception ignored) {}
    }

    /**
     * Queue a frame if Gemini hasn't finished setup yet, otherwise send it through the watchdog.
     * Without this, frames sent in the ~200–500 ms window between auth-success and setupComplete
     * are silently dropped — the user's first words vanish. Pending frames are capped at
     * MAX_PENDING_FRAMES to prevent unbounded memory growth if setup never completes.
     */
    private void queueOrSend(WebSocketSession session, okhttp3.WebSocket geminiWs, String json, String label) {
        if (Boolean.TRUE.equals(geminiReady.get(session.getId()))) {
            sendWithWatchdog(session, geminiWs, json, label);
            return;
        }
        Deque<String> q = pendingFrames.get(session.getId());
        if (q == null) {
            // Race: cleanup happened between map lookup and our get. Drop silently.
            return;
        }
        if (q.size() >= MAX_PENDING_FRAMES) {
            q.pollFirst();
        }
        q.offerLast(json);
    }

    private void flushPendingFrames(WebSocketSession session) {
        Deque<String> q = pendingFrames.remove(session.getId());
        if (q == null || q.isEmpty()) return;
        okhttp3.WebSocket ws = geminiSockets.get(session.getId());
        if (ws == null) return;
        log.info("Flushing {} pending frames for session={}", q.size(), session.getId());
        String frame;
        while ((frame = q.pollFirst()) != null) {
            sendWithWatchdog(session, ws, frame, "buffered");
        }
    }

    /**
     * Send to Gemini with a watchdog. OkHttp's send() returns false when the outbound
     * queue is full or the socket is closing — but a half-open peer can leave a frame
     * "accepted" yet never delivered. This wraps the send so that a stuck call is
     * detected within GEMINI_SEND_DEADLINE_MS and the browser session is torn down
     * with a clear error rather than wedging silently.
     */
    private boolean sendWithWatchdog(WebSocketSession browserSession, okhttp3.WebSocket geminiWs, String payload, String label) {
        ScheduledFuture<?> deadline = scheduler.schedule(() -> {
            log.error("Gemini send watchdog tripped: session={} label={}", browserSession.getId(), label);
            safeSend(browserSession, Map.of("type", "error", "message", "Gemini send timed out (" + label + ")"));
            closeSession(browserSession, CloseStatus.SERVER_ERROR);
        }, GEMINI_SEND_DEADLINE_MS, TimeUnit.MILLISECONDS);
        try {
            boolean accepted = geminiWs.send(payload);
            if (!accepted) {
                log.warn("Gemini send rejected (queue full or socket closing): session={} label={}", browserSession.getId(), label);
            }
            return accepted;
        } finally {
            deadline.cancel(false);
        }
    }
}
