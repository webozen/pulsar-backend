package com.pulsar.callintel.api;

import com.pulsar.callintel.services.GeminiSummarizer;
import com.pulsar.callintel.services.GeminiSummarizer.Summary;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import com.pulsar.kernel.voice.CallEndedEvent;
import com.pulsar.kernel.voice.RecordingFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallIntelControllerTest {

    private TenantDataSources tenantDs;
    private GeminiSummarizer summarizer;
    private RecordingFetcher rcFetcher;
    private GeminiKeyResolver geminiKeyResolver;
    private DataSource mockDs;

    // A minimal tenant bound to the thread-local for every test that hits TenantContext.require()
    private static final TenantInfo TENANT = new TenantInfo(
        1L, "test-slug", "Test Clinic", "test_db",
        Set.of("call-intel"), false
    );

    @BeforeEach
    void setUp() {
        tenantDs  = mock(TenantDataSources.class);
        summarizer = mock(GeminiSummarizer.class);
        mockDs    = mock(DataSource.class);

        rcFetcher = mock(RecordingFetcher.class);
        when(rcFetcher.id()).thenReturn("ringcentral");

        geminiKeyResolver = mock(GeminiKeyResolver.class);
        // Default to "key configured" — individual tests can override.
        when(geminiKeyResolver.resolveForDb(anyString()))
            .thenReturn(new GeminiKeyResolver.Resolution("AIza-test", GeminiKeyResolver.Source.TENANT));

        when(tenantDs.forDb(anyString())).thenReturn(mockDs);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private CallIntelController controller() {
        return new CallIntelController(tenantDs, summarizer, List.of(rcFetcher), geminiKeyResolver);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests — onCallEnded event listener
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void onCallEnded_inserts_to_queue() {
        CallEndedEvent event = new CallEndedEvent(
            "test-slug", "test_db", "ringcentral", "{\"event\":\"SessionCompleted\"}"
        );

        try (MockedConstruction<JdbcTemplate> jdbcMock =
                 Mockito.mockConstruction(JdbcTemplate.class, (jdbc, ctx) ->
                     when(jdbc.update(contains("call_intel_webhook_queue"),
                         any(Object[].class))).thenReturn(1))) {

            CallIntelController ctrl = controller();
            ctrl.onCallEnded(event);

            JdbcTemplate captured = jdbcMock.constructed().get(0);
            verify(captured).update(
                contains("call_intel_webhook_queue"),
                any(Object[].class)
            );
        }
    }

    @Test
    void onCallEnded_does_not_throw_on_db_failure() {
        CallEndedEvent event = new CallEndedEvent(
            "test-slug", "test_db", "ringcentral", "{}"
        );
        when(tenantDs.forDb("test_db")).thenThrow(new RuntimeException("db down"));

        CallIntelController ctrl = controller();
        // Must not throw — event listeners must not propagate
        assertDoesNotThrow(() -> ctrl.onCallEnded(event));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests — /process
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void process_missing_gemini_key_returns_424() {
        // Gemini key is now resolved via the centralized GeminiKeyResolver
        // (Phase 1 of credential centralization). Override the BeforeEach
        // default to simulate "no key configured".
        when(geminiKeyResolver.resolveForDb(anyString()))
            .thenReturn(new GeminiKeyResolver.Resolution(null, GeminiKeyResolver.Source.NONE));

        CallIntelController ctrl = controller();
        CallIntelController.ProcessRequest req = new CallIntelController.ProcessRequest(
            "ringcentral", null, null, null, null,
            null, null, null, null, "Hello transcript"
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> ctrl.process(req));

        assertEquals(HttpStatus.FAILED_DEPENDENCY, ex.getStatusCode());
    }

    @Test
    void process_with_transcript_calls_summarizer() throws IOException {
        Summary fakeSummary = new Summary(
            "Staff: Hi\nPatient: Hi",
            "Quick call",
            List.of("Follow up"),
            "positive",
            "appointment"
        );
        // Gemini key now flows via the resolver — override the default mock
        // to return the value the summarizer expects.
        when(geminiKeyResolver.resolveForDb(anyString()))
            .thenReturn(new GeminiKeyResolver.Resolution("gemini-key-xyz", GeminiKeyResolver.Source.TENANT));
        when(summarizer.summarizeTranscript(eq("gemini-key-xyz"), anyString()))
            .thenReturn(fakeSummary);

        CallIntelController ctrl = controller();
        CallIntelController.ProcessRequest req = new CallIntelController.ProcessRequest(
            "ringcentral", "sess-001", "inbound", "+15550001111", 60,
            null, null, null, null, "Staff: Hi\nPatient: Hi"
        );

        try (MockedConstruction<JdbcTemplate> jdbcMock =
                 Mockito.mockConstruction(JdbcTemplate.class, (jdbc, ctx) -> {
                     // INSERT call_intel_entry
                     when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
                     // LAST_INSERT_ID()
                     when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
                 })) {

            ResponseEntity<Map<String, Object>> resp = ctrl.process(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("Quick call", resp.getBody().get("summary"));

            verify(summarizer).summarizeTranscript(eq("gemini-key-xyz"), anyString());
            verify(summarizer, never()).summarizeAudio(any(), any(), any());
        }
    }

    @Test
    void process_with_recording_ref_calls_fetcher_then_summarizer() throws IOException {
        byte[] fakeAudio = new byte[]{1, 2, 3};
        Summary fakeSummary = new Summary(
            "Staff: Hi\nPatient: Hello",
            "Audio call summary",
            List.of("Send invoice"),
            "neutral",
            "billing"
        );
        when(rcFetcher.fetch(eq("rec-123"), any(DataSource.class))).thenReturn(fakeAudio);
        when(geminiKeyResolver.resolveForDb(anyString()))
            .thenReturn(new GeminiKeyResolver.Resolution("gemini-key-xyz", GeminiKeyResolver.Source.TENANT));
        when(summarizer.summarizeAudio(eq("gemini-key-xyz"), eq(fakeAudio), anyString()))
            .thenReturn(fakeSummary);

        CallIntelController ctrl = controller();
        CallIntelController.ProcessRequest req = new CallIntelController.ProcessRequest(
            "ringcentral", "sess-002", "inbound", "+15550001111", 120,
            "rec-123", null, null, "audio/mpeg", null
        );

        try (MockedConstruction<JdbcTemplate> jdbcMock =
                 Mockito.mockConstruction(JdbcTemplate.class, (jdbc, ctx) -> {
                     when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
                     when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
                 })) {

            ResponseEntity<Map<String, Object>> resp = ctrl.process(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("Audio call summary", resp.getBody().get("summary"));

            verify(rcFetcher).fetch(eq("rec-123"), any(DataSource.class));
            verify(summarizer).summarizeAudio(eq("gemini-key-xyz"), eq(fakeAudio), eq("audio/mpeg"));
            verify(summarizer, never()).summarizeTranscript(any(), any());
        }
    }
}
