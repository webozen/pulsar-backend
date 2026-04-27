package com.pulsar.voice.ringcentral;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RingCentralTokenServiceTest {

    private OkHttpClient mockHttp;
    private RingCentralTokenService service;
    private DataSource mockDs;

    @BeforeEach
    void setUp() {
        mockHttp = mock(OkHttpClient.class);
        service  = new RingCentralTokenService(mockHttp, new ObjectMapper());
        mockDs   = mock(DataSource.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Stubs JdbcTemplate to return the given rows for queryForList. */
    private MockedConstruction<JdbcTemplate> stubJdbc(List<Map<String, Object>> rows) {
        return Mockito.mockConstruction(JdbcTemplate.class, (jdbc, ctx) ->
            when(jdbc.queryForList(anyString())).thenReturn(rows));
    }

    /** Stubs JdbcTemplate with a query stub AND a verify-able update stub. */
    private MockedConstruction<JdbcTemplate> stubJdbcWithUpdate(
            List<Map<String, Object>> rows) {
        return Mockito.mockConstruction(JdbcTemplate.class, (jdbc, ctx) -> {
            when(jdbc.queryForList(anyString())).thenReturn(rows);
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        });
    }

    private Response buildHttpResponse(int code, String body) {
        return new Response.Builder()
            .request(new Request.Builder().url("https://platform.ringcentral.com/restapi/oauth/token").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(code == 200 ? "OK" : "Error")
            .body(ResponseBody.create(body, okhttp3.MediaType.get("application/json")))
            .build();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void getAccessToken_returns_null_when_no_row_exists() {
        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of())) {
            assertNull(service.getAccessToken(mockDs));
        }
    }

    @Test
    void getAccessToken_returns_null_when_access_token_is_blank() {
        var row = Map.<String, Object>of("oauth_access_token", "",
            "oauth_refresh_token", "ref", "oauth_expires_at", Timestamp.from(Instant.now().plusSeconds(3600)),
            "rc_client_id", "cid", "rc_client_secret", "csec");
        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            assertNull(service.getAccessToken(mockDs));
        }
    }

    @Test
    void getAccessToken_returns_stored_token_when_not_expired() {
        var row = Map.<String, Object>of(
            "oauth_access_token", "valid-token",
            "oauth_refresh_token", "ref-token",
            "oauth_expires_at", Timestamp.from(Instant.now().plusSeconds(3600)),
            "rc_client_id", "cid",
            "rc_client_secret", "csec"
        );
        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            assertEquals("valid-token", service.getAccessToken(mockDs));
        }
        // HTTP should never be called
        verifyNoInteractions(mockHttp);
    }

    @Test
    void getAccessToken_returns_stored_token_when_expiry_is_null() {
        // When oauth_expires_at is null we assume token is still valid
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("oauth_access_token", "no-expiry-token");
        row.put("oauth_refresh_token", "ref");
        row.put("oauth_expires_at", null);
        row.put("rc_client_id", "cid");
        row.put("rc_client_secret", "csec");

        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            assertEquals("no-expiry-token", service.getAccessToken(mockDs));
        }
        verifyNoInteractions(mockHttp);
    }

    @Test
    void getAccessToken_skips_refresh_when_client_credentials_missing() {
        // Token expired but no client credentials → return existing token as-is
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("oauth_access_token", "expired-token");
        row.put("oauth_refresh_token", "ref-token");
        row.put("oauth_expires_at", Timestamp.from(Instant.now().minusSeconds(60)));
        row.put("rc_client_id", null);
        row.put("rc_client_secret", null);

        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            assertEquals("expired-token", service.getAccessToken(mockDs));
        }
        verifyNoInteractions(mockHttp);
    }

    @Test
    void getAccessToken_skips_refresh_when_refresh_token_missing() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("oauth_access_token", "expired-token");
        row.put("oauth_refresh_token", null);
        row.put("oauth_expires_at", Timestamp.from(Instant.now().minusSeconds(60)));
        row.put("rc_client_id", "cid");
        row.put("rc_client_secret", "csec");

        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            assertEquals("expired-token", service.getAccessToken(mockDs));
        }
        verifyNoInteractions(mockHttp);
    }

    @Test
    void getAccessToken_refreshes_and_returns_new_token() throws Exception {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("oauth_access_token", "old-access");
        row.put("oauth_refresh_token", "old-refresh");
        row.put("oauth_expires_at", Timestamp.from(Instant.now().minusSeconds(60)));
        row.put("rc_client_id", "client-id");
        row.put("rc_client_secret", "client-secret");

        String rcResponse = "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}";
        Response fakeResp = buildHttpResponse(200, rcResponse);

        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(fakeResp);

        try (MockedConstruction<JdbcTemplate> jdbc = stubJdbcWithUpdate(List.of(row))) {
            String result = service.getAccessToken(mockDs);
            assertEquals("new-access", result);

            // Verify that update was called on one of the JdbcTemplate instances
            boolean updatedDb = jdbc.constructed().stream()
                .anyMatch(t -> {
                    try {
                        verify(t, atLeastOnce()).update(anyString(), any(Object[].class));
                        return true;
                    } catch (org.mockito.exceptions.base.MockitoAssertionError e) {
                        return false;
                    }
                });
            assertTrue(updatedDb, "Expected DB to be updated with new tokens");
        }
    }

    @Test
    void getAccessToken_falls_back_to_old_token_on_http_failure() throws Exception {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("oauth_access_token", "old-access");
        row.put("oauth_refresh_token", "old-refresh");
        row.put("oauth_expires_at", Timestamp.from(Instant.now().minusSeconds(60)));
        row.put("rc_client_id", "cid");
        row.put("rc_client_secret", "csec");

        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenThrow(new IOException("network error"));

        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            // Should not throw; should fall back to old token
            assertEquals("old-access", service.getAccessToken(mockDs));
        }
    }

    @Test
    void getAccessToken_falls_back_to_old_token_on_rc_error_response() throws Exception {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("oauth_access_token", "old-access");
        row.put("oauth_refresh_token", "old-refresh");
        row.put("oauth_expires_at", Timestamp.from(Instant.now().minusSeconds(60)));
        row.put("rc_client_id", "cid");
        row.put("rc_client_secret", "csec");

        Response errorResp = buildHttpResponse(401, "{\"error\":\"invalid_grant\"}");
        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(errorResp);

        try (MockedConstruction<JdbcTemplate> ignored = stubJdbc(List.of(row))) {
            assertEquals("old-access", service.getAccessToken(mockDs));
        }
    }
}
