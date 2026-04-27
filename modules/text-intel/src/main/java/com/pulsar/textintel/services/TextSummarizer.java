package com.pulsar.textintel.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Gemini-powered summarizer for text/SMS threads. Same generateContent
 * pattern as call-intel's GeminiSummarizer; different system prompt so the
 * output suits a SMS conversation rather than a phone-call transcript.
 */
@Component
public class TextSummarizer {

    private static final String MODEL = System.getenv()
        .getOrDefault("GEMINI_TEXT_SUMMARY_MODEL", "gemini-2.5-flash");
    private static final String ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Summary(
        String summary,
        String sentiment,
        String intent,
        List<String> actionItems
    ) {}

    public Summary summarize(String geminiKey, List<Map<String, Object>> messages) throws IOException {
        StringBuilder transcript = new StringBuilder();
        for (var m : messages) {
            String dir = String.valueOf(m.getOrDefault("direction", ""));
            String body = String.valueOf(m.getOrDefault("body", "")).replaceAll("\\s+", " ").trim();
            transcript.append(dir.equalsIgnoreCase("inbound") ? "Patient" : "Staff")
                      .append(": ").append(body).append("\n");
        }

        Map<String, Object> payload = Map.of(
            "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt()))),
            "contents", List.of(Map.of("role", "user", "parts", List.of(
                Map.of("text", "SMS thread between dental-practice staff and a patient:\n\n" + transcript)
            ))),
            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.2)
        );

        String url = ENDPOINT_TEMPLATE.formatted(MODEL, geminiKey);
        Request req = new Request.Builder()
            .url(url)
            .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON))
            .build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("gemini_" + resp.code() + ": " + safe(text));
            JsonNode root = mapper.readTree(text);
            JsonNode part = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (part.isMissingNode() || part.isNull()) throw new IOException("gemini_no_text: " + safe(text));
            JsonNode out = mapper.readTree(part.asText());
            return new Summary(
                out.path("summary").asText(""),
                out.path("sentiment").asText("neutral"),
                out.path("intent").asText("other"),
                parseList(out.path("actionItems"))
            );
        }
    }

    private static String systemPrompt() {
        return """
            You are a triage assistant for a dental-practice front desk reading
            an SMS conversation with a patient. Return a single JSON object,
            no prose:

            {
              "summary":     "1-2 sentence plain-English overview a staff member would read",
              "sentiment":   "positive | neutral | negative | urgent",
              "intent":      "appointment | billing | clinical question | complaint | confirmation | other",
              "actionItems": ["short imperative phrases like 'Confirm Tuesday slot for Maritta'"]
            }

            If the thread is empty or meaningless, return valid JSON with empty
            fields and an empty actionItems array.
            """;
    }

    private List<String> parseList(JsonNode n) {
        if (!n.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(n.spliterator(), false)
            .map(JsonNode::asText).filter(s -> s != null && !s.isBlank()).toList();
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    /** Required UTF-8 helper (kept for parity with other senders). */
    public byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
