package com.pulsar.translate.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.translate.TranslateSettings;
import com.pulsar.translate.TranslateSettingsService;
import com.pulsar.translate.history.HistoryService;
import com.pulsar.translate.history.TranscriptCollector;
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
    private final TranslateSettingsService settingsService;
    private final HistoryService historyService;
    private final ConcurrentHashMap<String, TranscriptCollector> collectors = new ConcurrentHashMap<>();
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
    private final ConcurrentHashMap<String, SessionTimer> sessionTimers = new ConcurrentHashMap<>();

    private static final long GEMINI_SEND_DEADLINE_MS = 5_000;

    // Maps the language NAME emitted by Gemini in its `[Punjabi]` / `[Hindi+English]`
    // bracket marker (per the auto-detect clause in SystemInstructions) back to the
    // ISO 639-1 code our transliterator keys on. Only includes languages we can
    // actually transliterate — others fall through and the transcript stays as-is.
    private static final java.util.regex.Pattern LANG_MARKER =
        java.util.regex.Pattern.compile("^\\s*\\[([A-Za-z+ ]+)\\]");
    private static final Map<String, String> LANG_NAME_TO_CODE = Map.ofEntries(
        Map.entry("punjabi", "pa"), Map.entry("hindi", "hi"), Map.entry("marathi", "mr"),
        Map.entry("nepali", "ne"), Map.entry("sanskrit", "sa"), Map.entry("tamil", "ta"),
        Map.entry("telugu", "te"), Map.entry("kannada", "kn"), Map.entry("malayalam", "ml"),
        Map.entry("bengali", "bn"), Map.entry("gujarati", "gu"), Map.entry("arabic", "ar"),
        Map.entry("urdu", "ur"), Map.entry("russian", "ru"), Map.entry("ukrainian", "uk"),
        Map.entry("bulgarian", "bg")
    );

    // Per-type inbound frame caps. Audio frames are base64-encoded PCM @ 16kHz / 4096 samples
    // (~11 KB per chunk), so 400 KB is a generous cap that absorbs back-pressure spikes
    // without letting a malicious client spam memory through the 2 MB WS buffer.
    private static final int MAX_AUDIO_FRAME_BYTES = 400 * 1024;
    private static final int MAX_TEXT_FRAME_BYTES = 8 * 1024;
    private static final int MAX_CONTROL_FRAME_BYTES = 4 * 1024;

    public GeminiProxyHandler(JwtService jwtService, TenantDataSources tenantDs,
                              TenantRepository tenantRepo, TranslateSettingsService settingsService,
                              HistoryService historyService) {
        this.jwtService = jwtService;
        this.tenantDs = tenantDs;
        this.tenantRepo = tenantRepo;
        this.settingsService = settingsService;
        this.historyService = historyService;
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

        if ("extend-session".equals(type)) {
            SessionTimer timer = sessionTimers.get(session.getId());
            if (timer != null) timer.extend();
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
        session.getAttributes().put("tenant_db", tenant.dbName());

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

        TranslateSettings settings = settingsService.forDb(tenant.dbName());
        startSessionTimer(session, settings);
        session.getAttributes().put("translate_settings", settings);
        // Stored so handleGeminiMessage can transliterate input transcripts
        // back to native script when Gemini's STT romanizes Indic/CJK speech.
        session.getAttributes().put("source_lang", sourceLang);
        // Conversation-bootstrap gate: when the user has picked no language at
        // all (auto sourceLang AND no explicit patient targetLang), Gemini's
        // built-in `PATIENT LANGUAGE BOOTSTRAP` clause is unreliable — it
        // sometimes still translates staff's English into Hindi/Spanish/etc.
        // based on a guess. We suppress translation/audio/output-transcription
        // frames until the patient has actually spoken (detected via a
        // non-ASCII inputTranscription). Picker is the FE-side switch: any
        // explicit choice ('pa', 'hi', etc.) drops the gate immediately.
        boolean autoMode = "auto".equals(sourceLang) && "conversation".equals(mode) && "en".equals(targetLang);
        session.getAttributes().put("auto_mode", autoMode);
        collectors.put(session.getId(), historyService.start(tenant.dbName(), sourceLang, targetLang, mode));

        String systemInstruction = SystemInstructions.build(mode, sourceLang, targetLang);
        openGeminiConnection(session, geminiKey, systemInstruction);
        log.info("Translate session: tenant={} mode={} src={} tgt={} model={} duration={}min", slug, mode, sourceLang, targetLang, GEMINI_MODEL, settings.sessionDurationMin());
    }

    private void startSessionTimer(WebSocketSession session, TranslateSettings settings) {
        SessionTimer timer = new SessionTimer(
            session.getId(),
            settings.sessionDurationMin(),
            settings.extendGrantMin(),
            settings.maxExtends(),
            scheduler,
            (sid, frame) -> safeSend(session, frame),
            (sid) -> closeSession(session, CloseStatus.NORMAL)
        );
        sessionTimers.put(session.getId(), timer);
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

                        // Tighter end-of-speech detection — without this, a 30+ second
                        // monologue collapses into a single huge turn (and one giant
                        // bubble in the kiosk). HIGH sensitivity + 600ms silence cuts
                        // turns at natural sentence breaks, so each utterance gets its
                        // own input/translation pair on the FE.
                        Map<String, Object> realtimeInputConfig = Map.of(
                            "automatic_activity_detection", Map.of(
                                "end_of_speech_sensitivity", "END_SENSITIVITY_HIGH",
                                "silence_duration_ms", 600
                            )
                        );

                        Map<String, Object> setupInner = new LinkedHashMap<>();
                        setupInner.put("model", "models/" + GEMINI_MODEL);
                        setupInner.put("generation_config", genConfig);
                        setupInner.put("realtime_input_config", realtimeInputConfig);
                        // Sliding-window compression: trims old turns when the context
                        // approaches the model limit. Insurance for the 30-min session.
                        setupInner.put("context_window_compression", Map.of("sliding_window", new LinkedHashMap<>()));
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

            TranscriptCollector collector = collectors.get(session.getId());

            // Bootstrap-gate: in pure auto mode, suppress translation/audio/output
            // frames until the patient has actually spoken (detected via a non-ASCII
            // inputTranscription). The corresponding system_instruction clause asks
            // Gemini to do this, but it's unreliable; we enforce it server-side.
            boolean autoMode = Boolean.TRUE.equals(session.getAttributes().get("auto_mode"));
            boolean patientSpoke = Boolean.TRUE.equals(session.getAttributes().get("patient_spoke"));

            Map<String, Object> it = (Map<String, Object>) sc.get("inputTranscription");
            if (autoMode && !patientSpoke && it != null && it.get("text") != null) {
                String t = (String) it.get("text");
                if (containsNonAsciiLetter(t)) {
                    session.getAttributes().put("patient_spoke", true);
                    patientSpoke = true;
                    log.info("Bootstrap gate opened: session={} firstNonAscii={}", session.getId(), t.length() > 30 ? t.substring(0, 30) + "..." : t);
                }
            }
            boolean gateOpen = !autoMode || patientSpoke;

            Map<String, Object> mt = (Map<String, Object>) sc.get("modelTurn");
            if (mt != null && gateOpen) {
                var parts = (List<Map<String, Object>>) mt.get("parts");
                if (parts != null) for (var p : parts) {
                    String text = (String) p.get("text");
                    if (text != null && !Boolean.TRUE.equals(p.get("thought"))) {
                        maybeEmitLanguageHint(session, text);
                        boolean isFinal = Boolean.TRUE.equals(sc.get("turnComplete"));
                        safeSend(session, Map.of("type", "translation", "text", text, "final", isFinal));
                        if (isFinal && collector != null) collector.recordOutput(text);
                    }
                    Map<String, Object> id = (Map<String, Object>) p.get("inlineData");
                    if (id != null) safeSend(session, Map.of("type", "audio", "data", id.get("data"), "mimeType", id.get("mimeType")));
                }
            }

            if (it != null && it.get("text") != null) {
                String t = (String) it.get("text");
                // Picker is the source of truth for script. We only transliterate
                // when the user has explicitly chosen a source language; auto-detect
                // mode passes through unchanged because Gemini's first-turn language
                // ID is unreliable for closely-related pairs (Punjabi↔Hindi etc.)
                // and a wrong ICU pass corrupts the transcript.
                String srcLang = (String) session.getAttributes().get("source_lang");
                if (srcLang != null && !"auto".equals(srcLang)) {
                    t = TranscriptTransliterator.maybeTransliterate(t, srcLang);
                }
                safeSend(session, Map.of("type", "input-transcription", "text", t));
                if (collector != null) collector.recordInput(t);
            }

            Map<String, Object> ot = (Map<String, Object>) sc.get("outputTranscription");
            if (ot != null && ot.get("text") != null && gateOpen) {
                String t = (String) ot.get("text");
                safeSend(session, Map.of("type", "output-transcription", "text", t));
                // For voice modes, the audio output transcription is the canonical output;
                // record it here so we don't miss output when modelTurn.text is absent.
                if (collector != null) collector.recordOutput(t);
            }

            if (Boolean.TRUE.equals(sc.get("turnComplete")) && gateOpen)
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

    /**
     * In auto-detect mode the system_instruction asks Gemini to prefix its
     * first translation with a `[Punjabi]` or `[Punjabi+English]` marker.
     * We forward that as a one-shot hint so the FE can offer the staff a
     * "Switch script to Punjabi?" prompt — but we deliberately do NOT use
     * it to drive transliteration ourselves, because Gemini frequently
     * misclassifies closely-related languages (Punjabi↔Hindi, Marathi↔Hindi,
     * Urdu↔Hindi). The picker remains the source of truth for script.
     */
    private void maybeEmitLanguageHint(WebSocketSession session, String text) {
        if (Boolean.TRUE.equals(session.getAttributes().get("lang_hint_sent"))) return;
        String src = (String) session.getAttributes().get("source_lang");
        if (src != null && !"auto".equals(src)) return;
        var m = LANG_MARKER.matcher(text);
        if (!m.find()) return;
        String name = m.group(1).toLowerCase();
        if (name.contains("+")) name = name.split("\\+")[0].trim();
        String code = LANG_NAME_TO_CODE.get(name);
        if (code == null) return;
        session.getAttributes().put("lang_hint_sent", true);
        safeSend(session, Map.of("type", "language-hint", "lang", code, "name", capitalize(name)));
        log.info("Emitted language hint: session={} lang={}", session.getId(), code);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Returns true if the text contains any non-ASCII letter character.
     * Used to detect that the patient — speaking a non-English language —
     * has been transcribed by Gemini in their native script. Whitespace and
     * punctuation outside ASCII don't count (some Indic punctuation can
     * appear even in romanized output).
     */
    private static boolean containsNonAsciiLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 && Character.isLetter(c)) return true;
        }
        return false;
    }

    private void cleanup(String id) {
        authPending.remove(id);
        ScheduledFuture<?> t = authTimeouts.remove(id);
        if (t != null) t.cancel(false);
        geminiReady.remove(id);
        pendingFrames.remove(id);
        SessionTimer timer = sessionTimers.remove(id);
        TranscriptCollector collector = collectors.remove(id);
        if (collector != null) {
            int extendsUsed = timer != null ? timer.extendsUsed() : 0;
            historyService.finalize(collector, extendsUsed);
        }
        if (timer != null) timer.cancel();
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
