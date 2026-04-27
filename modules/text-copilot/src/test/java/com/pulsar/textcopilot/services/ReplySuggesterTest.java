package com.pulsar.textcopilot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplySuggesterTest {

    private ReplySuggester suggester;
    private OkHttpClient mockHttp;

    @BeforeEach
    void setUp() throws Exception {
        suggester = new ReplySuggester();
        mockHttp = mock(OkHttpClient.class);

        // Replace the private final `http` field with the mock via reflection
        Field field = ReplySuggester.class.getDeclaredField("http");
        field.setAccessible(true);
        field.set(suggester, mockHttp);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void stubResponse(int code, String body) throws IOException {
        Response fakeResponse = new Response.Builder()
            .request(new Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(code == 200 ? "OK" : "Error")
            .body(ResponseBody.create(body, MediaType.get("application/json")))
            .build();

        Call fakeCall = mock(Call.class);
        when(fakeCall.execute()).thenReturn(fakeResponse);
        when(mockHttp.newCall(any(Request.class))).thenReturn(fakeCall);
    }

    private String geminiResponse(String innerJson) throws Exception {
        ObjectMapper om = new ObjectMapper();
        String escapedInner = om.writeValueAsString(innerJson);
        return """
            {"candidates":[{"content":{"parts":[{"text":%s}]}}]}
            """.formatted(escapedInner);
    }

    private List<Map<String, Object>> sampleConversation() {
        return List.of(
            Map.of("direction", "inbound",  "body", "Can I change my appointment to Thursday?"),
            Map.of("direction", "outbound", "body", "Let me check availability for you.")
        );
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void suggest_returns_parsed_suggestions() throws Exception {
        String inner = """
            {
              "suggestions": [
                {"text": "Thursday at 2pm works great — shall I book it?", "tone": "friendly"},
                {"text": "We have Thursday at 2pm or 4pm open. Which suits you?", "tone": "brief"},
                {"text": "Happy to move you to Thursday — 2pm or 4pm available.", "tone": "empathetic"}
              ]
            }
            """;
        stubResponse(200, geminiResponse(inner));

        List<ReplySuggester.Suggestion> results = suggester.suggest("fake-key", sampleConversation(), null, 3);

        assertEquals(3, results.size());
        assertEquals("Thursday at 2pm works great — shall I book it?", results.get(0).text());
        assertEquals("friendly", results.get(0).tone());
    }

    @Test
    void suggest_includes_draft_in_prompt() throws Exception {
        String inner = """
            {"suggestions":[{"text":"Sounds good, see you then!","tone":"brief"}]}
            """;
        stubResponse(200, geminiResponse(inner));

        String draft = "Thursday at 3pm is available for you";
        suggester.suggest("fake-key", sampleConversation(), draft, 2);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(captor.capture());
        String sentBody = new String(requestBodyBytes(captor.getValue().body()));
        assertTrue(sentBody.contains(draft),
            "Expected draft text in prompt body, got: " + sentBody);
    }

    @Test
    void suggest_handles_no_draft_gracefully() throws Exception {
        String inner = """
            {"suggestions":[{"text":"We have Thursday open — interested?","tone":"friendly"}]}
            """;
        stubResponse(200, geminiResponse(inner));

        // null draft should produce the "has not started a reply" fallback line
        suggester.suggest("fake-key", sampleConversation(), null, 2);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(captor.capture());
        String sentBody = new String(requestBodyBytes(captor.getValue().body()));
        assertTrue(sentBody.contains("Staff has not started a reply yet"),
            "Expected null-draft placeholder in prompt, got: " + sentBody);
    }

    @Test
    void suggest_throws_on_non_200() throws Exception {
        stubResponse(500, "{\"error\":\"internal server error\"}");

        IOException ex = assertThrows(IOException.class,
            () -> suggester.suggest("fake-key", sampleConversation(), "some draft", 2));
        assertTrue(ex.getMessage().contains("gemini_500"),
            "Expected message to contain 'gemini_500', got: " + ex.getMessage());
    }

    @Test
    void suggest_returns_empty_list_when_suggestions_absent() throws Exception {
        // Inner JSON has no "suggestions" key — service should return empty list
        String inner = """
            {"unexpected_field": "oops"}
            """;
        stubResponse(200, geminiResponse(inner));

        List<ReplySuggester.Suggestion> results =
            suggester.suggest("fake-key", sampleConversation(), "draft", 2);

        assertNotNull(results);
        assertTrue(results.isEmpty(),
            "Expected empty list when 'suggestions' key is absent");
    }

    // ---------------------------------------------------------------------------
    // Private utility
    // ---------------------------------------------------------------------------

    private static byte[] requestBodyBytes(okhttp3.RequestBody body) throws IOException {
        okio.Buffer buf = new okio.Buffer();
        body.writeTo(buf);
        return buf.readByteArray();
    }
}
