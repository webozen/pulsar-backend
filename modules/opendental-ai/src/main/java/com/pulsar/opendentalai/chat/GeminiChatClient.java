package com.pulsar.opendentalai.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Thin HTTP client for Gemini's generateContent endpoint.
 * Used by OpendentalAiChatController for text-in / text-out chat
 * with function calling — no audio, no WebSocket.
 */
@Component
public class GeminiChatClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Turn(String role, String text) {}

    public static class GeminiChatException extends RuntimeException {
        public GeminiChatException(String msg) { super(msg); }
    }

    /**
     * Single-shot generate: send conversation history + system instruction,
     * returns the raw response body as a parsed Map for the controller to interpret.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(
        String apiKey,
        String systemInstruction,
        List<Turn> history,
        List<Map<String, Object>> functionDeclarations
    ) throws Exception {
        List<Map<String, Object>> contents = history.stream()
            .map(t -> Map.of(
                "role", t.role(),
                "parts", List.of(Map.of("text", t.text()))
            ))
            .collect(Collectors.toList());

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("contents", contents);
        body.put("tools", List.of(Map.of("function_declarations", functionDeclarations)));

        String json = mapper.writeValueAsString(body);
        log.debug("Gemini chat request: {} chars", json.length());

        Request req = new Request.Builder()
            .url(BASE + "?key=" + apiKey)
            .post(RequestBody.create(json, JSON_MT))
            .build();

        try (Response resp = http.newCall(req).execute()) {
            String responseBody = resp.body() != null ? resp.body().string() : "{}";
            if (!resp.isSuccessful()) {
                log.warn("Gemini chat error {}: {}", resp.code(), responseBody.substring(0, Math.min(200, responseBody.length())));
                throw new GeminiChatException("Gemini HTTP " + resp.code() + ": " + responseBody);
            }
            return mapper.readValue(responseBody, new TypeReference<>() {});
        }
    }
}
