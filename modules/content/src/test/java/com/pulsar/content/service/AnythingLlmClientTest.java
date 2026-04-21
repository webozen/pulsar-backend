package com.pulsar.content.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** AnythingLlmClient tests — drives the RestTemplate via MockRestServiceServer. */
class AnythingLlmClientTest {

    private static final String BASE = "http://llm-test:3001";
    private static final String KEY = "test-key-abc";

    // ---- isConfigured ----------------------------------------------------

    @Test
    void isConfigured_trueWhenKeyNonBlank() {
        assertTrue(new AnythingLlmClient(BASE, KEY).isConfigured());
    }

    @Test
    void isConfigured_falseWhenKeyBlank() {
        assertFalse(new AnythingLlmClient(BASE, "").isConfigured());
        assertFalse(new AnythingLlmClient(BASE, "   ").isConfigured());
    }

    @Test
    void isConfigured_falseWhenKeyNull() {
        assertFalse(new AnythingLlmClient(BASE, null).isConfigured());
    }

    // ---- Unconfigured short-circuits ------------------------------------

    @Test
    void getOrCreateWorkspace_unconfigured_returnsSlugAndMakesNoCalls() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, "");
        MockRestServiceServer server = bind(client);
        // Server has no expectations; any call would fail verification.
        assertEquals("acme", client.getOrCreateWorkspace("acme"));
        server.verify();
    }

    @Test
    void pushTextDocument_unconfigured_returnsNull() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, "");
        MockRestServiceServer server = bind(client);
        assertNull(client.pushTextDocument("acme", "Title", "text"));
        server.verify();
    }

    @Test
    void uploadFile_unconfigured_returnsNull() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, "");
        MockRestServiceServer server = bind(client);
        assertNull(client.uploadFile("acme", "x.pdf", "application/pdf", new byte[]{1}));
        server.verify();
    }

    @Test
    void removeDocument_unconfigured_noOp() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, "");
        MockRestServiceServer server = bind(client);
        client.removeDocument("acme", "doc");
        server.verify();
    }

    @Test
    void chat_unconfigured_returnsGuidanceMessage() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, "");
        MockRestServiceServer server = bind(client);
        String resp = client.chat("acme", "hi");
        assertTrue(resp.contains("LLM not configured"));
        server.verify();
    }

    // ---- getOrCreateWorkspace -------------------------------------------

    @Test
    void getOrCreateWorkspace_workspaceExists_noPostIssued() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer " + KEY))
            .andRespond(withSuccess("{\"workspace\":{\"slug\":\"acme\"}}", MediaType.APPLICATION_JSON));

        String slug = client.getOrCreateWorkspace("acme");

        assertEquals("acme", slug);
        server.verify();
    }

    @Test
    void getOrCreateWorkspace_missing_postsCreate() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/api/v1/workspace/new"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer " + KEY))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String slug = client.getOrCreateWorkspace("acme");

        assertEquals("acme", slug);
        server.verify();
    }

    // ---- pushTextDocument ------------------------------------------------

    @Test
    void pushTextDocument_postsRawTextThenEmbeddings() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        // getOrCreateWorkspace: GET succeeds.
        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE + "/api/v1/document/raw-text"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"document\":{\"location\":\"custom-documents/foo.json\"}}",
                MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE + "/api/v1/workspace/acme/update-embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String location = client.pushTextDocument("acme", "Title", "body text");

        assertEquals("custom-documents/foo.json", location);
        server.verify();
    }

    @Test
    void pushTextDocument_serverError_returnsNullGracefully() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/document/raw-text"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertNull(client.pushTextDocument("acme", "Title", "body"));
        server.verify();
    }

    // ---- uploadFile ------------------------------------------------------

    @Test
    void uploadFile_postsMultipartThenEmbeddings() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE + "/api/v1/document/upload"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"documents\":[{\"location\":\"custom-documents/file.pdf-123.json\"}]}",
                MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE + "/api/v1/workspace/acme/update-embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String location = client.uploadFile("acme", "file.pdf", "application/pdf", new byte[]{1, 2});

        assertEquals("custom-documents/file.pdf-123.json", location);
        server.verify();
    }

    // ---- removeDocument --------------------------------------------------

    @Test
    void removeDocument_postsUpdateEmbeddingsWithDeletes() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme/update-embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.removeDocument("acme", "custom-documents/foo.json");
        server.verify();
    }

    @Test
    void removeDocument_nullLocation_noOp() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);
        client.removeDocument("acme", null);
        server.verify(); // no expectations, no calls
    }

    @Test
    void removeDocument_serverError_swallowed() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme/update-embeddings"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Should not throw.
        client.removeDocument("acme", "doc");
        server.verify();
    }

    // ---- chat ------------------------------------------------------------

    @Test
    void chat_returnsTextResponse() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE + "/api/v1/workspace/acme/chat"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"textResponse\":\"hello back\"}",
                MediaType.APPLICATION_JSON));

        String resp = client.chat("acme", "hello");
        assertEquals("hello back", resp);
        server.verify();
    }

    @Test
    void chat_serverError_returnsFallbackMessage() {
        AnythingLlmClient client = new AnythingLlmClient(BASE, KEY);
        MockRestServiceServer server = bind(client);

        server.expect(requestTo(BASE + "/api/v1/workspace/acme"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE + "/api/v1/workspace/acme/chat"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        String resp = client.chat("acme", "hello");
        assertNotNull(resp);
        assertTrue(resp.startsWith("Chat unavailable"), "got: " + resp);
        server.verify();
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Bind a MockRestServiceServer to the client's private RestTemplate.
     * AnythingLlmClient constructs its own RestTemplate, so we reach in via reflection.
     */
    private static MockRestServiceServer bind(AnythingLlmClient client) {
        try {
            Field f = AnythingLlmClient.class.getDeclaredField("rest");
            f.setAccessible(true);
            RestTemplate rest = (RestTemplate) f.get(client);
            return MockRestServiceServer.createServer(rest);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to access rest field on AnythingLlmClient", e);
        }
    }
}
