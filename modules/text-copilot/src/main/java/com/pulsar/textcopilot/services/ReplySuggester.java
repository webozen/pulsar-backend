package com.pulsar.textcopilot.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * One-shot Gemini call returning N alternative SMS reply phrasings. Same
 * generateContent endpoint as the other Gemini-text modules; different system
 * prompt + JSON shape.
 */
@Component
public class ReplySuggester {

    private static final String MODEL = System.getenv()
        .getOrDefault("GEMINI_TEXT_COPILOT_MODEL", "gemini-2.5-flash");
    private static final String ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Suggestion(String text, String tone) {}

    public List<Suggestion> suggest(String geminiKey, List<Map<String, Object>> recent, String draft, int n)
            throws IOException {
        StringBuilder convo = new StringBuilder();
        for (var m : recent) {
            String dir = String.valueOf(m.getOrDefault("direction", ""));
            String body = String.valueOf(m.getOrDefault("body", "")).replaceAll("\\s+", " ").trim();
            convo.append(dir.equalsIgnoreCase("inbound") ? "Patient" : "Staff")
                 .append(": ").append(body).append("\n");
        }
        String draftLine = draft == null || draft.isBlank()
            ? "(Staff has not started a reply yet — propose what to say next.)"
            : "Staff is drafting: \"" + draft + "\"\nKeep their intent, refine the wording.";

        Map<String, Object> payload = Map.of(
            "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt(n)))),
            "contents", List.of(Map.of("role", "user", "parts", List.of(
                Map.of("text", "Conversation so far (most recent last):\n\n" + convo + "\n" + draftLine)
            ))),
            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.5)
        );

        String url = ENDPOINT_TEMPLATE.formatted(MODEL, geminiKey);
        Request req = new Request.Builder().url(url)
            .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON)).build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("gemini_" + resp.code() + ": " + safe(text));
            JsonNode part = mapper.readTree(text)
                .path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (part.isMissingNode() || part.isNull()) throw new IOException("gemini_no_text");
            JsonNode out = mapper.readTree(part.asText());
            JsonNode arr = out.path("suggestions");
            if (!arr.isArray()) return List.of();
            List<Suggestion> result = new java.util.ArrayList<>();
            for (var node : arr) {
                String t = node.path("text").asText("");
                String tone = node.path("tone").asText("friendly");
                if (!t.isBlank()) result.add(new Suggestion(t, tone));
            }
            return result;
        }
    }

    private static String systemPrompt(int n) {
        return """
            You are an SMS reply assistant for a dental-practice front desk.
            Read the conversation and propose %d distinct alternative reply
            phrasings the staff member could send. Each must be:
              - Under 160 characters (one SMS segment)
              - In first person, friendly, professional, never apologetic
              - Specific — reference the patient's actual ask, no generic
                "Got it!" replies
              - Free of medical advice; redirect to the dentist if the patient
                asks something clinical

            Return JSON only:
            { "suggestions": [
                { "text": "...", "tone": "friendly|empathetic|firm|brief" },
                ...
            ] }
            """.formatted(n);
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
