package com.pulsar.callintel.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeminiSummarizerTest {

    private GeminiSummarizer summarizer;
    private OkHttpClient mockHttp;

    @BeforeEach
    void setUp() throws Exception {
        summarizer = new GeminiSummarizer();
        mockHttp   = mock(OkHttpClient.class);

        Field field = GeminiSummarizer.class.getDeclaredField("http");
        field.setAccessible(true);
        field.set(summarizer, mockHttp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void stubResponse(int code, String body) throws IOException {
        Response fakeResp = new Response.Builder()
            .request(new Request.Builder().url("https://x.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(code == 200 ? "OK" : "Error")
            .body(ResponseBody.create(body, MediaType.get("application/json")))
            .build();

        Call fakeCall = mock(Call.class);
        when(fakeCall.execute()).thenReturn(fakeResp);
        when(mockHttp.newCall(any(Request.class))).thenReturn(fakeCall);
    }

    /**
     * Wraps an inner JSON string as the text value inside a Gemini
     * candidates response, properly JSON-string-escaping the inner content.
     */
    private String geminiJson(String innerJson) throws Exception {
        ObjectMapper om = new ObjectMapper();
        String escaped = om.writeValueAsString(innerJson); // adds quotes + escapes
        return """
            {"candidates":[{"content":{"parts":[{"text":%s}]}}]}
            """.formatted(escaped);
    }

    private static String innerSummaryJson(String transcript) {
        return """
            {
              "transcript": "%s",
              "summary": "Test summary",
              "actionItems": ["Call back"],
              "sentiment": "positive",
              "patientIntent": "appointment"
            }
            """.formatted(transcript == null ? "" : transcript.replace("\"", "\\\"")
                                                              .replace("\n", "\\n"));
    }

    private static byte[] captureRequestBody(okhttp3.RequestBody body) throws IOException {
        Buffer buf = new Buffer();
        body.writeTo(buf);
        return buf.readByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarize_transcript_returns_parsed_summary() throws Exception {
        String inner = innerSummaryJson("Staff: Hi\nPatient: Hi");
        stubResponse(200, geminiJson(inner));

        GeminiSummarizer.Summary result =
            summarizer.summarizeTranscript("fake-key", "Staff: Hi\nPatient: Hi");

        assertEquals("Test summary", result.summary());
        assertEquals("positive", result.sentiment());
        assertEquals("appointment", result.patientIntent());
        assertEquals(List.of("Call back"), result.actionItems());
        assertTrue(result.transcript().contains("Staff: Hi"));
    }

    @Test
    void summarize_audio_encodes_bytes_as_base64_part() throws Exception {
        String inner = innerSummaryJson("Staff: Hi\nPatient: Hello");
        stubResponse(200, geminiJson(inner));

        byte[] audio = "fake-audio-bytes".getBytes(StandardCharsets.UTF_8);
        summarizer.summarizeAudio("fake-key", audio, "audio/mpeg");

        // Capture the outgoing request and verify inlineData with base64
        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(captor.capture());
        String sentBody = new String(captureRequestBody(captor.getValue().body()),
            StandardCharsets.UTF_8);

        assertTrue(sentBody.contains("inlineData"),
            "Expected 'inlineData' key in request body, got: " + sentBody);

        String expectedB64 = Base64.getEncoder().encodeToString(audio);
        assertTrue(sentBody.contains(expectedB64),
            "Expected base64-encoded audio in request body");
    }

    @Test
    void summarize_transcript_throws_on_non_200() throws Exception {
        stubResponse(400, "{\"error\":{\"message\":\"API key not valid\"}}");

        IOException ex = assertThrows(IOException.class,
            () -> summarizer.summarizeTranscript("bad-key", "some transcript"));

        assertTrue(ex.getMessage().contains("Gemini 400"),
            "Expected message to contain 'Gemini 400', got: " + ex.getMessage());
    }

    @Test
    void summarize_throws_when_no_text_part() throws Exception {
        // 200 response but the body has no candidates array
        stubResponse(200, "{}");

        IOException ex = assertThrows(IOException.class,
            () -> summarizer.summarizeTranscript("fake-key", "hello"));

        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    void summarize_transcript_preserves_fallback_transcript_when_gemini_omits_it() throws Exception {
        // Inner JSON deliberately has no "transcript" field
        String innerNoTranscript = """
            {
              "summary": "Short call",
              "actionItems": [],
              "sentiment": "neutral",
              "patientIntent": "other"
            }
            """;
        stubResponse(200, geminiJson(innerNoTranscript));

        String inputTranscript = "Staff: Hello\nPatient: Bye";
        GeminiSummarizer.Summary result =
            summarizer.summarizeTranscript("fake-key", inputTranscript);

        // When Gemini omits "transcript", the code falls back to the input transcript
        assertEquals(inputTranscript, result.transcript(),
            "Expected fallback to input transcript when Gemini omits the field");
        assertEquals("Short call", result.summary());
    }
}
