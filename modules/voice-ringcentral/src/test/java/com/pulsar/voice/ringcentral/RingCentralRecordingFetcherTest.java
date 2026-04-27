package com.pulsar.voice.ringcentral;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RingCentralRecordingFetcherTest {

    private MockWebServer server;
    private RingCentralRecordingFetcher fetcher;
    private DataSource mockDs;
    private RingCentralTokenService mockTokenService;

    @BeforeEach
    void setUp() throws IOException {
        server           = new MockWebServer();
        server.start();
        mockDs           = mock(DataSource.class);
        mockTokenService = mock(RingCentralTokenService.class);
        fetcher          = new RingCentralRecordingFetcher(mockTokenService);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_with_http_ref_downloads_and_returns_bytes() throws Exception {
        byte[] expected = "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody(new String(expected, StandardCharsets.UTF_8)));

        String ref = server.url("/recording.mp3").toString();
        when(mockTokenService.getAccessToken(any())).thenReturn("token-abc");

        byte[] result = fetcher.fetch(ref, mockDs);
        assertArrayEquals(expected, result);
    }

    @Test
    void fetch_sends_authorization_bearer_header() throws Exception {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("audio"));

        String ref = server.url("/recording.mp3").toString();
        when(mockTokenService.getAccessToken(any())).thenReturn("my-access-token");

        fetcher.fetch(ref, mockDs);

        RecordedRequest recorded = server.takeRequest();
        String authHeader = recorded.getHeader("Authorization");
        assertNotNull(authHeader, "Expected Authorization header to be sent");
        assertEquals("Bearer my-access-token", authHeader);
    }

    @Test
    void fetch_throws_when_no_oauth_token() {
        when(mockTokenService.getAccessToken(any())).thenReturn(null);

        IOException ex = assertThrows(IOException.class,
            () -> fetcher.fetch("https://example.com/rec.mp3", mockDs));

        assertEquals("ringcentral_oauth_not_configured", ex.getMessage());
    }

    @Test
    void fetch_throws_on_non_200_response() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

        String ref = server.url("/recording.mp3").toString();
        when(mockTokenService.getAccessToken(any())).thenReturn("valid-token");

        IOException ex = assertThrows(IOException.class,
            () -> fetcher.fetch(ref, mockDs));

        assertTrue(ex.getMessage().contains("ringcentral_recording_fetch_401"),
            "Expected error message with status 401, got: " + ex.getMessage());
    }

    @Test
    void fetch_follows_redirect_to_final_url() throws Exception {
        byte[] finalBytes = "redirected-audio".getBytes(StandardCharsets.UTF_8);
        String finalPath  = "/final-recording.mp3";

        server.enqueue(new MockResponse()
            .setResponseCode(302)
            .addHeader("Location", server.url(finalPath).toString()));
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody(new String(finalBytes, StandardCharsets.UTF_8)));

        String ref = server.url("/redirect-me.mp3").toString();
        when(mockTokenService.getAccessToken(any())).thenReturn("tok-redirect");

        // HttpURLConnection with setInstanceFollowRedirects(true) should follow
        byte[] result = fetcher.fetch(ref, mockDs);
        assertArrayEquals(finalBytes, result);
    }
}
