package com.pulsar.opendentalai.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.opendentalai.schema.SchemaCatalog;
import com.pulsar.opendentalai.tools.RunQueryTool;
import com.pulsar.opendentalai.tools.RunQueryTool.Invocation;
import com.pulsar.opendentalai.tools.RunQueryTool.ToolResponse;
import io.jsonwebtoken.Claims;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Live-API proxy with function calling. Handshake mirrors the Translate module's
 * GeminiProxyHandler — browser authenticates with a pulsar_jwt, backend pulls
 * the tenant's Gemini + OD keys, opens a Live API session, and forwards text
 * back and forth. The key difference vs. translate: we declare one tool
 * ({@code run_opendental_query}), intercept toolCall messages, execute the
 * query via {@link RunQueryTool}, and send a toolResponse back to Gemini.
 */
@Component
public class OpendentalAiHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(OpendentalAiHandler.class);

    private static final String GEMINI_WS_BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final String GEMINI_MODEL =
        System.getenv().getOrDefault("GEMINI_LIVE_MODEL", "gemini-3.1-flash-live-preview");
    private static final int AUTH_TIMEOUT_SEC = 10;

    private final JwtService jwt;
    private final TenantRepository tenantRepo;
    private final TenantDataSources tenantDs;
    private final SchemaCatalog catalog;
    private final RunQueryTool runQueryTool;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient okClient = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private final ConcurrentHashMap<String, WebSocket> geminiSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> authPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> authTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** Captures the last question the user asked so the audit log can attribute tool calls to it. */
    private final ConcurrentHashMap<String, String> lastQuestion = new ConcurrentHashMap<>();

    private record Session(
        String tenantSlug,
        String userEmail,
        String odDeveloperKey,
        String odCustomerKey,
        String tenantDbName
    ) {}

    public OpendentalAiHandler(JwtService jwt, TenantRepository tenantRepo, TenantDataSources tenantDs,
                               SchemaCatalog catalog, RunQueryTool runQueryTool) {
        this.jwt = jwt;
        this.tenantRepo = tenantRepo;
        this.tenantDs = tenantDs;
        this.catalog = catalog;
        this.runQueryTool = runQueryTool;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Browser WS connected: session={} uri={} remote={}",
            session.getId(), session.getUri(), session.getRemoteAddress());
        authPending.put(session.getId(), true);
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (authPending.containsKey(session.getId())) {
                send(session, Map.of("type", "error", "message", "auth timeout"));
                closeSession(session, CloseStatus.POLICY_VIOLATION);
            }
        }, AUTH_TIMEOUT_SEC, TimeUnit.SECONDS);
        authTimeouts.put(session.getId(), timeout);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Browser WS closed: session={} status={}", session.getId(), status);
        cleanup(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = mapper.readValue(message.getPayload(), new TypeReference<>() {});
        if (authPending.containsKey(session.getId())) {
            handleAuth(session, msg);
            return;
        }
        WebSocket gw = geminiSockets.get(session.getId());
        if (gw == null) return;

        String type = (String) msg.get("type");
        if ("audio".equals(type)) {
            // Streaming 20ms PCM@16kHz base64 frames from the mic to Gemini.
            String data = (String) msg.get("data");
            if (data == null || data.isEmpty()) return;
            gw.send(mapper.writeValueAsString(Map.of(
                "realtimeInput", Map.of("audio", Map.of(
                    "data", data, "mimeType", "audio/pcm;rate=16000"))
            )));
        } else if ("text".equals(type)) {
            // Kept for debugging only — the kiosk UI uses audio input exclusively.
            String text = (String) msg.get("text");
            if (text == null || text.isBlank()) return;
            lastQuestion.put(session.getId(), text);
            gw.send(mapper.writeValueAsString(Map.of(
                "clientContent", Map.of(
                    "turns", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", text)))),
                    "turnComplete", true
                )
            )));
        } else if ("end".equals(type)) {
            closeSession(session, CloseStatus.NORMAL);
        }
    }

    private void handleAuth(WebSocketSession session, Map<String, Object> msg) {
        if (!"auth".equals(msg.get("type"))) { deny(session, "expected_auth"); return; }
        ScheduledFuture<?> t = authTimeouts.remove(session.getId());
        if (t != null) t.cancel(false);
        authPending.remove(session.getId());

        String token = (String) msg.get("token");
        if (token == null) { deny(session, "missing_token"); return; }
        Claims claims;
        try { claims = jwt.parse(token); }
        catch (Exception e) { deny(session, "invalid_token"); return; }

        String slug = claims.get("slug", String.class);
        String email = claims.get("email", String.class);
        if (slug == null) { deny(session, "invalid_token"); return; }

        var rec = tenantRepo.findBySlug(slug).orElse(null);
        if (rec == null) { deny(session, "tenant_not_found"); return; }
        if (!rec.activeModules().contains("opendental-ai")) { deny(session, "module_not_active"); return; }

        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(rec.dbName()));
        var rows = jdbc.queryForList(
            "SELECT gemini_key, od_developer_key, od_customer_key FROM opendental_ai_config WHERE id = 1"
        );
        if (rows.isEmpty()) { deny(session, "not_onboarded"); return; }
        String geminiKey = (String) rows.get(0).get("gemini_key");
        String odDev     = (String) rows.get(0).get("od_developer_key");
        String odCust    = (String) rows.get(0).get("od_customer_key");

        sessions.put(session.getId(), new Session(slug, email, odDev, odCust, rec.dbName()));
        session.getAttributes().put("tenant_slug", slug);
        MDC.put("tenant_id", slug);

        openGemini(session, geminiKey, SystemInstructions.build(catalog, slug));
        log.info("OpenDental AI session opened: tenant={} model={}", slug, GEMINI_MODEL);
    }

    private void openGemini(WebSocketSession browser, String geminiKey, String systemInstruction) {
        String url = GEMINI_WS_BASE + "?key=" + geminiKey;
        Request req = new Request.Builder().url(url).build();
        Map<String, String> mdcSnap = MDC.getCopyOfContextMap();

        okClient.newWebSocket(req, new WebSocketListener() {
            private void mdc() { if (mdcSnap != null) MDC.setContextMap(mdcSnap); }

            @Override public void onOpen(WebSocket ws, Response response) {
                try {
                    mdc();
                    log.info("Gemini WS opened for session={} model={}", browser.getId(), GEMINI_MODEL);
                    geminiSockets.put(browser.getId(), ws);
                    // gemini-3.1-flash-live-preview is audio-first: TEXT modality
                    // rejects at setup (1011), AUDIO modality ignores text-only input.
                    // The kiosk streams mic → PCM → Gemini → audio back, and we also
                    // request input/output transcription so the UI can render the
                    // spoken question + answer as text alongside the audio playback.
                    Map<String, Object> genConfig = new LinkedHashMap<>();
                    genConfig.put("response_modalities", List.of("AUDIO"));
                    genConfig.put("speech_config", Map.of("voice_config", Map.of(
                        "prebuilt_voice_config", Map.of("voice_name", "Kore"))));

                    Map<String, Object> tool = Map.of(
                        "function_declarations", List.of(Map.of(
                            "name", "run_opendental_query",
                            "description",
                                "Run a read-only SELECT statement against the tenant's Open Dental MySQL database " +
                                "via the OpenDental Query API. Use for any factual question about patients, " +
                                "appointments, production, scheduling, recalls, or billing. Respect the schema " +
                                "in the system prompt — only SELECTs are allowed; writes are rejected.",
                            "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "sql", Map.of(
                                        "type", "string",
                                        "description", "A single MySQL SELECT statement. No INSERT/UPDATE/DELETE/DDL."
                                    ),
                                    "explanation", Map.of(
                                        "type", "string",
                                        "description", "One-sentence English description of what this query computes."
                                    )
                                ),
                                "required", List.of("sql", "explanation")
                            )
                        ))
                    );

                    Map<String, Object> setup = new LinkedHashMap<>();
                    setup.put("model", "models/" + GEMINI_MODEL);
                    setup.put("generation_config", genConfig);
                    setup.put("input_audio_transcription", new LinkedHashMap<>());
                    setup.put("output_audio_transcription", new LinkedHashMap<>());
                    setup.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
                    setup.put("tools", List.of(tool));

                    String setupJson = mapper.writeValueAsString(Map.of("setup", setup));
                    log.info("Sending setup to Gemini: size={} bytes", setupJson.length());
                    ws.send(setupJson);
                } catch (Exception e) {
                    log.error("Gemini setup failed: {}", e.getMessage(), e);
                    send(browser, Map.of("type", "error", "message", "setup failed: " + e.getMessage()));
                } finally { MDC.clear(); }
            }

            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    mdc();
                    log.info("← Gemini msg: {}", text.length() > 1000 ? text.substring(0, 1000) + "…" : text);
                    handleGemini(browser, ws, text);
                } finally { MDC.clear(); }
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                try {
                    mdc();
                    String t = bytes.utf8();
                    log.info("← Gemini binary-as-utf8: {}", t.length() > 1000 ? t.substring(0, 1000) + "…" : t);
                    handleGemini(browser, ws, t);
                } finally { MDC.clear(); }
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                log.info("Gemini WS closed code={} reason={}", code, reason);
                send(browser, Map.of("type", "error", "message", "gemini closed " + code + " " + reason));
                scheduler.schedule(() -> closeSession(browser, CloseStatus.NORMAL), 150, TimeUnit.MILLISECONDS);
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                String msg = response != null ? response.code() + " " + response.message() : (t == null ? "unknown" : t.getMessage());
                log.error("Gemini WS failure: {}", msg, t);
                send(browser, Map.of("type", "error", "message", "gemini failed: " + msg));
                scheduler.schedule(() -> closeSession(browser, CloseStatus.SERVER_ERROR), 150, TimeUnit.MILLISECONDS);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleGemini(WebSocketSession browser, WebSocket geminiWs, String raw) {
        try {
            Map<String, Object> msg = mapper.readValue(raw, new TypeReference<>() {});
            if (msg.containsKey("setupComplete") || msg.containsKey("setup_complete")) {
                send(browser, Map.of("type", "ready"));
                return;
            }

            // Tool calls from Gemini — execute and send the response back.
            Map<String, Object> toolCall = (Map<String, Object>) msg.get("toolCall");
            if (toolCall == null) toolCall = (Map<String, Object>) msg.get("tool_call");
            if (toolCall != null) {
                List<Map<String, Object>> fns = (List<Map<String, Object>>)
                    (toolCall.containsKey("functionCalls") ? toolCall.get("functionCalls") : toolCall.get("function_calls"));
                if (fns != null) {
                    handleToolCalls(browser, geminiWs, fns);
                }
                return;
            }

            Map<String, Object> sc = (Map<String, Object>) msg.get("serverContent");
            if (sc == null) return;

            // Input transcription — what the user's mic said (useful for logging / UI).
            Map<String, Object> it = (Map<String, Object>) sc.get("inputTranscription");
            if (it != null) {
                String t = (String) it.get("text");
                if (t != null && !t.isEmpty()) {
                    // Capture the final question for the audit log when the tool fires later.
                    lastQuestion.merge(browser.getId(), t, (prev, add) -> prev + add);
                    send(browser, Map.of("type", "user-transcription", "text", t));
                }
            }
            // Output transcription — textual version of Gemini's audio response.
            Map<String, Object> ot = (Map<String, Object>) sc.get("outputTranscription");
            if (ot != null) {
                String t = (String) ot.get("text");
                if (t != null && !t.isEmpty()) {
                    send(browser, Map.of("type", "assistant", "text", t, "final", false));
                }
            }

            Map<String, Object> mt = (Map<String, Object>) sc.get("modelTurn");
            if (mt != null) {
                var parts = (List<Map<String, Object>>) mt.get("parts");
                if (parts != null) for (var p : parts) {
                    String text = (String) p.get("text");
                    if (text != null && !Boolean.TRUE.equals(p.get("thought"))) {
                        send(browser, Map.of("type", "assistant", "text", text,
                            "final", Boolean.TRUE.equals(sc.get("turnComplete"))));
                    }
                    // Forward audio chunks so the kiosk can play Gemini's spoken response.
                    Map<String, Object> id = (Map<String, Object>) p.get("inlineData");
                    if (id != null) {
                        send(browser, Map.of("type", "audio",
                            "data", id.get("data"), "mimeType", id.get("mimeType")));
                    }
                }
            }
            if (Boolean.TRUE.equals(sc.get("turnComplete"))) {
                send(browser, Map.of("type", "turn-complete"));
                // Clear the question buffer — next turn gets a fresh one.
                lastQuestion.remove(browser.getId());
            }
        } catch (Exception e) {
            log.error("Gemini parse error: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleToolCalls(WebSocketSession browser, WebSocket geminiWs, List<Map<String, Object>> fns) throws Exception {
        Session s = sessions.get(browser.getId());
        if (s == null) return;

        List<Map<String, Object>> responses = new java.util.ArrayList<>();
        for (Map<String, Object> fn : fns) {
            String id = (String) fn.get("id");
            String name = (String) fn.get("name");
            Map<String, Object> args = (Map<String, Object>) fn.get("args");
            if (!"run_opendental_query".equals(name)) {
                responses.add(Map.of("id", id, "name", name,
                    "response", Map.of("status", "unknown_function")));
                continue;
            }
            String sql = args == null ? null : (String) args.get("sql");
            send(browser, Map.of("type", "tool-call", "name", name, "args", args));

            ToolResponse resp = runQueryTool.run(new Invocation(
                browser.getId(), s.userEmail(), lastQuestion.get(browser.getId()),
                sql, s.odDeveloperKey(), s.odCustomerKey(),
                tenantDs.forDb(s.tenantDbName())
            ));

            Map<String, Object> respBody = new LinkedHashMap<>();
            respBody.put("status", resp.status());
            if (resp.result() != null) respBody.put("result", resp.result());
            if (resp.error()  != null) respBody.put("error", resp.error());
            responses.add(Map.of("id", id, "name", name, "response", respBody));

            send(browser, Map.of("type", "tool-result", "status", resp.status(),
                "rows", resp.result() == null ? null : ((Map<String, Object>) resp.result()).get("count")));
        }

        geminiWs.send(mapper.writeValueAsString(Map.of(
            "toolResponse", Map.of("functionResponses", responses)
        )));
    }

    private void cleanup(String id) {
        authPending.remove(id);
        ScheduledFuture<?> t = authTimeouts.remove(id);
        if (t != null) t.cancel(false);
        WebSocket g = geminiSockets.remove(id);
        if (g != null) { try { g.close(1000, "done"); } catch (Exception ignored) {} }
        sessions.remove(id);
        lastQuestion.remove(id);
    }

    private void deny(WebSocketSession session, String reason) {
        send(session, Map.of("type", "error", "message", reason));
        closeSession(session, CloseStatus.POLICY_VIOLATION);
    }

    private void send(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (session.isOpen()) synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception e) { log.debug("send failed: {}", e.getMessage()); }
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
        try { if (session.isOpen()) session.close(status); } catch (Exception ignored) {}
    }
}
