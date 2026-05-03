package com.pulsar.copilot.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.credentials.OpenDentalKeyResolver;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantRepository;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient.QueryRequest;
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
 * Live agent-assist Gemini proxy. Modeled on OpendentalAiHandler but runs
 * in TEXT response modality — Gemini never speaks back. The browser pipes
 * the staff microphone in; the handler emits {@code suggestion} events
 * (driven by tool calls) and live transcription back to the panel.
 *
 * <p>Single audio channel today (staff mic only) — RC's hosted Embeddable
 * doesn't expose the remote (patient) WebRTC track. When we hoist the
 * WebPhone SDK into the parent page, a second channel will land at
 * {@code /ws/copilot/patient} and merge here.
 */
@Component
public class CoPilotHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(CoPilotHandler.class);

    private static final String GEMINI_WS_BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final String GEMINI_MODEL =
        System.getenv().getOrDefault("GEMINI_LIVE_MODEL", "gemini-3.1-flash-live-preview");
    private static final int AUTH_TIMEOUT_SEC = 10;

    private static final String SYSTEM_INSTRUCTION = """
        You are a SILENT call-assistant for a dental-practice staff member who
        is on the phone with a patient. You hear the conversation through the
        staff member's microphone. NEVER produce conversational replies — you
        cannot speak on the call. You exist only to surface facts.

        When the patient asks a question that the `lookup_patient_by_phone` or
        `run_opendental_query` tool can answer, call the tool, then emit a
        single concise suggestion in the `suggestion` JSON tool ({title,
        body, actions[]}) — no narration around it. If no tool fits, stay
        silent. NEVER offer medical or treatment advice.
        """;

    private final JwtService jwt;
    private final TenantRepository tenantRepo;
    private final TenantDataSources tenantDs;
    private final OpendentalQueryClient odClient;
    private final GeminiKeyResolver geminiKeyResolver;
    private final OpenDentalKeyResolver opendentalKeyResolver;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient okClient = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private final ConcurrentHashMap<String, WebSocket> geminiSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> authPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> authTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String tenantSlug, String tenantDbName, String odDeveloperKey, String odCustomerKey) {}

    public CoPilotHandler(JwtService jwt, TenantRepository tenantRepo, TenantDataSources tenantDs,
                          OpendentalQueryClient odClient, GeminiKeyResolver geminiKeyResolver,
                          OpenDentalKeyResolver opendentalKeyResolver) {
        this.jwt = jwt;
        this.tenantRepo = tenantRepo;
        this.tenantDs = tenantDs;
        this.odClient = odClient;
        this.geminiKeyResolver = geminiKeyResolver;
        this.opendentalKeyResolver = opendentalKeyResolver;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Co-Pilot WS connected: session={} remote={}", session.getId(), session.getRemoteAddress());
        authPending.put(session.getId(), true);
        ScheduledFuture<?> t = scheduler.schedule(() -> {
            if (authPending.containsKey(session.getId())) {
                send(session, Map.of("type", "error", "message", "auth timeout"));
                closeSession(session, CloseStatus.POLICY_VIOLATION);
            }
        }, AUTH_TIMEOUT_SEC, TimeUnit.SECONDS);
        authTimeouts.put(session.getId(), t);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Co-Pilot WS closed: session={} status={}", session.getId(), status);
        cleanup(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = mapper.readValue(message.getPayload(), new TypeReference<>() {});
        if (authPending.containsKey(session.getId())) { handleAuth(session, msg); return; }
        WebSocket gw = geminiSockets.get(session.getId());
        if (gw == null) return;
        String type = (String) msg.get("type");
        if ("audio".equals(type)) {
            String data = (String) msg.get("data");
            if (data == null || data.isEmpty()) return;
            gw.send(mapper.writeValueAsString(Map.of(
                "realtimeInput", Map.of("audio", Map.of(
                    "data", data, "mimeType", "audio/pcm;rate=16000"))
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
        try { claims = jwt.parse(token); } catch (Exception e) { deny(session, "invalid_token"); return; }
        String slug = claims.get("slug", String.class);
        if (slug == null) { deny(session, "invalid_token"); return; }

        var rec = tenantRepo.findBySlug(slug).orElse(null);
        if (rec == null) { deny(session, "tenant_not_found"); return; }
        if (!rec.activeModules().contains("call-handling")) { deny(session, "module_not_active"); return; }

        // All credentials now flow through the centralized resolvers (tenant_credentials).
        OpenDentalKeyResolver.Keys odKeys = opendentalKeyResolver.resolveForDb(rec.dbName());
        if (!odKeys.isComplete()) { deny(session, "opendental_keys_missing"); return; }
        String odDev = odKeys.developerKey();
        String odCust = odKeys.customerKey();
        String geminiKey = geminiKeyResolver.resolveForDb(rec.dbName()).apiKey();
        if (geminiKey == null || geminiKey.isBlank()) { deny(session, "gemini_key_missing"); return; }
        // Open a JdbcTemplate against the tenant DB for the per-call session-log writes below.
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(rec.dbName()));

        sessions.put(session.getId(), new Session(slug, rec.dbName(), odDev, odCust));
        session.getAttributes().put("tenant_slug", slug);
        MDC.put("tenant_id", slug);

        // Open a session-log row right away; we'll patch it on close.
        try {
            jdbc.update(
                "INSERT INTO copilot_session_log (provider_id, provider_session_id) VALUES (?, ?)",
                "ringcentral", session.getId()
            );
        } catch (Exception ignored) { /* schema may not exist yet on first boot */ }

        openGemini(session, geminiKey);
        log.info("Co-Pilot session opened: tenant={} model={}", slug, GEMINI_MODEL);
    }

    private void openGemini(WebSocketSession browser, String geminiKey) {
        String url = GEMINI_WS_BASE + "?key=" + geminiKey;
        Request req = new Request.Builder().url(url).build();
        Map<String, String> mdcSnap = MDC.getCopyOfContextMap();

        okClient.newWebSocket(req, new WebSocketListener() {
            private void mdc() { if (mdcSnap != null) MDC.setContextMap(mdcSnap); }

            @Override public void onOpen(WebSocket ws, Response response) {
                try {
                    mdc();
                    log.info("Gemini WS opened for copilot session={}", browser.getId());
                    geminiSockets.put(browser.getId(), ws);

                    Map<String, Object> genConfig = new LinkedHashMap<>();
                    // TEXT response — silent assistant. Audio modality would have
                    // Gemini speak on the call; that's the opposite of co-pilot.
                    genConfig.put("response_modalities", List.of("TEXT"));

                    Map<String, Object> tools = Map.of(
                        "function_declarations", List.of(
                            Map.of(
                                "name", "lookup_patient_by_phone",
                                "description", "Find a patient in the practice's OpenDental DB by phone number. " +
                                    "Returns FName, LName, BalTotal, Email when matched.",
                                "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "phone", Map.of("type", "string", "description", "Phone in any format; we normalize.")
                                    ),
                                    "required", List.of("phone")
                                )
                            ),
                            Map.of(
                                "name", "run_opendental_query",
                                "description", "Run a read-only SELECT against the practice's OpenDental DB. " +
                                    "Use for any factual question (appointments, recalls, balances, treatments).",
                                "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "sql", Map.of("type", "string"),
                                        "explanation", Map.of("type", "string")
                                    ),
                                    "required", List.of("sql", "explanation")
                                )
                            ),
                            Map.of(
                                "name", "suggestion",
                                "description", "Emit a fact card to the staff member's screen. Call this AFTER " +
                                    "lookup/query when you have a concrete answer to surface.",
                                "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "title", Map.of("type", "string"),
                                        "body", Map.of("type", "string"),
                                        "actions", Map.of(
                                            "type", "array",
                                            "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                    "label", Map.of("type", "string"),
                                                    "kind", Map.of("type", "string", "description", "e.g. open-chart, hold-slot, copy")
                                                )
                                            )
                                        )
                                    ),
                                    "required", List.of("title", "body")
                                )
                            )
                        )
                    );

                    Map<String, Object> setup = new LinkedHashMap<>();
                    setup.put("model", "models/" + GEMINI_MODEL);
                    setup.put("generation_config", genConfig);
                    setup.put("system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))));
                    setup.put("tools", List.of(tools));
                    setup.put("input_audio_transcription", new LinkedHashMap<>());

                    ws.send(mapper.writeValueAsString(Map.of("setup", setup)));
                } catch (Exception e) {
                    log.error("Gemini setup failed: {}", e.getMessage());
                    closeSession(browser, CloseStatus.SERVER_ERROR);
                }
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) { mdc(); handleGeminiFrame(browser, ws, bytes.utf8()); }
            @Override public void onMessage(WebSocket ws, String text)     { mdc(); handleGeminiFrame(browser, ws, text); }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                mdc();
                log.info("Gemini WS closed code={} reason={}", code, reason);
                geminiSockets.remove(browser.getId());
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                mdc();
                log.warn("Gemini WS failed: {}", t.getMessage());
                send(browser, Map.of("type", "error", "message", "gemini_closed"));
                closeSession(browser, CloseStatus.SERVER_ERROR);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleGeminiFrame(WebSocketSession browser, WebSocket geminiWs, String json) {
        try {
            Map<String, Object> msg = mapper.readValue(json, new TypeReference<>() {});
            Map<String, Object> toolCall = (Map<String, Object>) msg.get("toolCall");
            if (toolCall == null) toolCall = (Map<String, Object>) msg.get("tool_call");
            if (toolCall != null) {
                List<Map<String, Object>> fns = (List<Map<String, Object>>) (
                    toolCall.containsKey("functionCalls") ? toolCall.get("functionCalls") : toolCall.get("function_calls")
                );
                if (fns != null) handleToolCalls(browser, geminiWs, fns);
                return;
            }
            Map<String, Object> sc = (Map<String, Object>) msg.get("serverContent");
            if (sc == null) return;
            Map<String, Object> it = (Map<String, Object>) sc.get("inputTranscription");
            if (it != null) {
                String txt = (String) it.get("text");
                if (txt != null && !txt.isEmpty()) send(browser, Map.of("type", "transcript", "speaker", "staff", "text", txt));
            }
        } catch (Exception e) {
            log.debug("copilot parse: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleToolCalls(WebSocketSession browser, WebSocket geminiWs, List<Map<String, Object>> fns) throws Exception {
        Session sess = sessions.get(browser.getId());
        if (sess == null) return;
        java.util.List<Map<String, Object>> responses = new java.util.ArrayList<>();
        for (Map<String, Object> fn : fns) {
            String name = (String) fn.get("name");
            Map<String, Object> args = (Map<String, Object>) fn.getOrDefault("args", Map.of());
            String id = (String) fn.get("id");
            Map<String, Object> result;
            switch (name) {
                case "lookup_patient_by_phone" -> result = lookupPatient(sess, (String) args.getOrDefault("phone", ""));
                case "run_opendental_query"     -> result = runQuery(sess, (String) args.getOrDefault("sql", ""));
                case "suggestion" -> {
                    // Forward straight to the browser; no Gemini round-trip needed.
                    send(browser, Map.of(
                        "type", "suggestion",
                        "title", args.getOrDefault("title", ""),
                        "body",  args.getOrDefault("body",  ""),
                        "actions", args.getOrDefault("actions", List.of())
                    ));
                    appendSuggestion(sess, browser.getId(), args);
                    result = Map.of("ok", true);
                }
                default -> result = Map.of("error", "unknown_tool:" + name);
            }
            responses.add(Map.of("id", id, "name", name, "response", Map.of("output", result)));
        }
        geminiWs.send(mapper.writeValueAsString(Map.of("toolResponse", Map.of("functionResponses", responses))));
    }

    private Map<String, Object> lookupPatient(Session s, String phone) {
        if (phone == null || phone.isBlank()) return Map.of("error", "missing_phone");
        String digits = phone.replaceAll("[^0-9]", "");
        String last10 = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        String sql = """
            SELECT PatNum, FName, LName, BalTotal, Email
            FROM patient
            WHERE REPLACE(REPLACE(REPLACE(REPLACE(WirelessPhone, '-', ''),' ',''),'(',''),')','') LIKE '%%%s%%'
               OR REPLACE(REPLACE(REPLACE(REPLACE(HmPhone,       '-', ''),' ',''),'(',''),')','') LIKE '%%%s%%'
               OR REPLACE(REPLACE(REPLACE(REPLACE(WkPhone,       '-', ''),' ',''),'(',''),')','') LIKE '%%%s%%'
            LIMIT 1;
            """.formatted(last10, last10, last10);
        try {
            var r = odClient.run(new QueryRequest(s.odDeveloperKey, s.odCustomerKey, sql));
            if (r.rows().isEmpty()) return Map.of("matched", false);
            var p = r.rows().get(0);
            return Map.of("matched", true, "row", p);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> runQuery(Session s, String sql) {
        if (sql == null || sql.isBlank()) return Map.of("error", "missing_sql");
        if (!sql.toUpperCase().trim().startsWith("SELECT")) return Map.of("error", "only_selects_allowed");
        try {
            var r = odClient.run(new QueryRequest(s.odDeveloperKey, s.odCustomerKey, sql));
            return Map.of("rows", r.rows(), "count", r.rowCount());
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private void appendSuggestion(Session s, String wsSessionId, Map<String, Object> args) {
        try {
            String json = mapper.writeValueAsString(args);
            new JdbcTemplate(tenantDs.forDb(s.tenantDbName)).update(
                "UPDATE copilot_session_log " +
                "SET suggestions = JSON_ARRAY_APPEND(COALESCE(suggestions, JSON_ARRAY()), '$', CAST(? AS JSON)) " +
                "WHERE provider_session_id = ?",
                json, wsSessionId
            );
        } catch (Exception ignored) { /* best-effort log */ }
    }

    private void cleanup(String id) {
        authPending.remove(id);
        ScheduledFuture<?> t = authTimeouts.remove(id);
        if (t != null) t.cancel(false);
        WebSocket g = geminiSockets.remove(id);
        if (g != null) { try { g.close(1000, "done"); } catch (Exception ignored) {} }
        Session s = sessions.remove(id);
        if (s != null) {
            try {
                new JdbcTemplate(tenantDs.forDb(s.tenantDbName)).update(
                    "UPDATE copilot_session_log SET ended_at = NOW() WHERE provider_session_id = ?", id
                );
            } catch (Exception ignored) {}
        }
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
        } catch (Exception e) { log.debug("copilot send failed: {}", e.getMessage()); }
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
        try { if (session.isOpen()) session.close(status); } catch (Exception ignored) {}
    }
}
