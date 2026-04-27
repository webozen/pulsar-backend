package com.pulsar.textintel.services;

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
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TextSummarizerTest {

    private TextSummarizer summarizer;
    private OkHttpClient mockHttp;

    @BeforeEach
    void setUp() throws Exception {
        summarizer = new TextSummarizer();
        mockHttp = mock(OkHttpClient.class);

        // Replace the private final `http` field with the mock via reflection
        Field field = TextSummarizer.class.getDeclaredField("http");
        field.setAccessible(true);
        field.set(summarizer, mockHttp);
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
        // The inner JSON is the text node value — it must be JSON-string-escaped
        ObjectMapper om = new ObjectMapper();
        String escapedInner = om.writeValueAsString(innerJson); // adds surrounding quotes + escapes
        return """
            {"candidates":[{"content":{"parts":[{"text":%s}]}}]}
            """.formatted(escapedInner);
    }

    private List<Map<String, Object>> sampleMessages() {
        return List.of(
            Map.of("direction", "inbound",  "body", "Can I reschedule my appointment?"),
            Map.of("direction", "outbound", "body", "Sure, how about Friday at 10am?"),
            Map.of("direction", "inbound",  "body", "Friday works perfectly, thanks!")
        );
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void summarize_returns_parsed_summary_with_action_items() throws Exception {
        String inner = """
            {
              "summary": "Patient requested a reschedule; staff offered Friday 10am which the patient accepted.",
              "sentiment": "positive",
              "intent": "appointment",
              "actionItems": ["Confirm Friday 10am slot", "Send calendar invite"]
            }
            """;
        stubResponse(200, geminiResponse(inner));

        TextSummarizer.Summary result = summarizer.summarize("fake-key", sampleMessages());

        assertEquals("Patient requested a reschedule; staff offered Friday 10am which the patient accepted.",
            result.summary());
        assertEquals("positive", result.sentiment());
        assertEquals("appointment", result.intent());
        assertEquals(List.of("Confirm Friday 10am slot", "Send calendar invite"), result.actionItems());
    }

    @Test
    void summarize_maps_direction_inbound_to_Patient_prefix_in_prompt() throws Exception {
        String inner = """
            {"summary":"ok","sentiment":"neutral","intent":"other","actionItems":[]}
            """;
        stubResponse(200, geminiResponse(inner));

        summarizer.summarize("fake-key", List.of(
            Map.of("direction", "inbound", "body", "Hello from the patient side")
        ));

        // Capture the outgoing request and verify its body contains "Patient: "
        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(captor.capture());
        Request captured = captor.getValue();
        String sentBody = captured.body() != null
            ? new String(requestBodyBytes(captured.body()))
            : "";
        assertTrue(sentBody.contains("Patient: "), "Expected 'Patient: ' prefix in prompt, got: " + sentBody);
    }

    @Test
    void summarize_maps_direction_outbound_to_Staff_prefix_in_prompt() throws Exception {
        String inner = """
            {"summary":"ok","sentiment":"neutral","intent":"other","actionItems":[]}
            """;
        stubResponse(200, geminiResponse(inner));

        summarizer.summarize("fake-key", List.of(
            Map.of("direction", "outbound", "body", "This is a staff reply")
        ));

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(captor.capture());
        String sentBody = new String(requestBodyBytes(captor.getValue().body()));
        assertTrue(sentBody.contains("Staff: "), "Expected 'Staff: ' prefix in prompt, got: " + sentBody);
    }

    @Test
    void summarize_throws_on_non_200_response() throws Exception {
        stubResponse(400, "{\"error\":\"bad request\"}");

        IOException ex = assertThrows(IOException.class,
            () -> summarizer.summarize("fake-key", sampleMessages()));
        assertTrue(ex.getMessage().contains("gemini_400"),
            "Expected message to contain 'gemini_400', got: " + ex.getMessage());
    }

    @Test
    void summarize_throws_when_candidates_missing() throws Exception {
        // Valid 200 but body has no candidates → path traversal yields MissingNode
        stubResponse(200, "{}");

        IOException ex = assertThrows(IOException.class,
            () -> summarizer.summarize("fake-key", sampleMessages()));
        assertTrue(ex.getMessage().contains("gemini_no_text"),
            "Expected message to contain 'gemini_no_text', got: " + ex.getMessage());
    }

    @Test
    void summarize_empty_action_items_when_array_absent() throws Exception {
        // Inner JSON has no actionItems key at all
        String inner = """
            {"summary":"Quick chat","sentiment":"neutral","intent":"other"}
            """;
        stubResponse(200, geminiResponse(inner));

        TextSummarizer.Summary result = summarizer.summarize("fake-key", sampleMessages());

        assertNotNull(result.actionItems());
        assertTrue(result.actionItems().isEmpty(),
            "Expected empty actionItems list when key is absent");
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
