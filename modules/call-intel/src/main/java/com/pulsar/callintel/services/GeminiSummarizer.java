package com.pulsar.callintel.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Non-Live Gemini client for post-call summarization. We hit
 * {@code generateContent} synchronously — unlike opendental-ai's Live
 * websocket, this is a one-shot request/response per call.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #summarizeTranscript} — when the caller already has text.
 *   <li>{@link #summarizeAudio} — when the caller has raw audio bytes; Gemini
 *       transcribes and summarizes in one pass.
 * </ul>
 */
@Component
public class GeminiSummarizer {

    private static final String MODEL = System.getenv()
        .getOrDefault("GEMINI_SUMMARY_MODEL", "gemini-2.5-flash");
    private static final String ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Summary(
        String transcript,
        String summary,
        List<String> actionItems,
        String sentiment,
        String patientIntent
    ) {}

    public Summary summarizeTranscript(String geminiKey, String transcript) throws IOException {
        Map<String, Object> payload = buildPayload(
            systemPrompt(),
            List.of(Map.of("text", "Transcript of a dental-practice phone call:\n\n" + transcript))
        );
        return call(geminiKey, payload, transcript);
    }

    public Summary summarizeAudio(String geminiKey, byte[] audioBytes, String mimeType) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(audioBytes);
        Map<String, Object> payload = buildPayload(
            systemPrompt(),
            List.of(
                Map.of("text", "Transcribe the following dental-practice phone call audio, " +
                    "then produce the JSON response described in your instructions."),
                Map.of("inlineData", Map.of("mimeType", mimeType, "data", b64))
            )
        );
        return call(geminiKey, payload, null);
    }

    // ───────────────────────────────────────────────────────────────────────

    private static String systemPrompt() {
        return """
            You are a call-intelligence assistant for a dental practice. Given the
            transcript (or audio) of a single phone call, return a single JSON
            object with these fields — no prose, no markdown, ONLY valid JSON:

            {
              "transcript":    "full verbatim transcript with speaker labels like 'Staff:' and 'Patient:' on each line",
              "summary":       "2-3 sentence plain-English summary a front-desk staff member would read",
              "actionItems":   ["short imperative phrases like 'Send treatment estimate to Maritta'"],
              "sentiment":     "one of: positive, neutral, negative, urgent",
              "patientIntent": "one short phrase: 'appointment', 'billing', 'clinical question', 'complaint', 'other'"
            }

            If the audio/transcript is silent or meaningless, still return valid
            JSON with empty strings and empty actionItems.
            """;
    }

    private Map<String, Object> buildPayload(String system, List<Map<String, Object>> parts) {
        return Map.of(
            "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
            "contents",          List.of(Map.of("role", "user", "parts", parts)),
            "generationConfig",  Map.of("responseMimeType", "application/json", "temperature", 0.2)
        );
    }

    private Summary call(String geminiKey, Map<String, Object> payload, String fallbackTranscript) throws IOException {
        String url = ENDPOINT_TEMPLATE.formatted(MODEL, geminiKey);
        String body = mapper.writeValueAsString(payload);
        Request req = new Request.Builder()
            .url(url)
            .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
            .build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("Gemini " + resp.code() + ": " + preview(text));
            }
            JsonNode root = mapper.readTree(text);
            JsonNode partText = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (partText.isMissingNode() || partText.isNull()) {
                throw new IOException("Gemini returned no text part: " + preview(text));
            }
            JsonNode out = mapper.readTree(partText.asText());
            return new Summary(
                out.path("transcript").asText(fallbackTranscript != null ? fallbackTranscript : ""),
                out.path("summary").asText(""),
                parseStringList(out.path("actionItems")),
                out.path("sentiment").asText("neutral"),
                out.path("patientIntent").asText("other")
            );
        }
    }

    private List<String> parseStringList(JsonNode n) {
        if (!n.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(n.spliterator(), false)
            .map(JsonNode::asText)
            .filter(s -> s != null && !s.isBlank())
            .toList();
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
