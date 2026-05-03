package com.pulsar.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class AnythingLlmClient {
    private static final Logger log = LoggerFactory.getLogger(AnythingLlmClient.class);

    private final com.pulsar.kernel.platform.PlatformSettingsService platformSettings;
    private final String baseUrlEnvFallback;
    private final String apiKeyEnvFallback;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public AnythingLlmClient(
        com.pulsar.kernel.platform.PlatformSettingsService platformSettings,
        @Value("${pulsar.anythingllm.url:http://localhost:3001}") String baseUrl,
        @Value("${pulsar.anythingllm.api-key:}") String apiKey
    ) {
        this.platformSettings = platformSettings;
        this.baseUrlEnvFallback = baseUrl;
        this.apiKeyEnvFallback = apiKey;
    }

    /** Resolve from platform_settings → env fallback. Per-call (no cache) so admin
     *  rotations land immediately. */
    private String baseUrl() {
        return platformSettings.resolveOrFallback("anythingllm.url", baseUrlEnvFallback);
    }
    private String apiKey() {
        return platformSettings.resolveOrFallback("anythingllm.api_key", apiKeyEnvFallback);
    }

    public boolean isConfigured() {
        String k = apiKey();
        return k != null && !k.isBlank();
    }

    public String getOrCreateWorkspace(String tenantSlug) {
        if (!isConfigured()) return tenantSlug;
        try {
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<Map> resp = rest.exchange(
                baseUrl() + "/api/v1/workspace/" + tenantSlug,
                HttpMethod.GET, req, Map.class
            );
            // AnythingLLM returns 200 with `{"workspace":[]}` (empty array) when
            // the workspace doesn't exist, and 200 with `{"workspace":{...}}`
            // (object) when it does. Just checking is2xxSuccessful would skip
            // creation and break chat with "Workspace X is not a valid workspace".
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object ws = resp.getBody().get("workspace");
                if (ws instanceof Map<?, ?>) return tenantSlug;
                if (ws instanceof List<?> list && !list.isEmpty()) return tenantSlug;
            }
        } catch (Exception ignored) {}
        try {
            HttpEntity<Map<String, String>> req = new HttpEntity<>(
                Map.of("name", tenantSlug), authHeaders()
            );
            rest.postForEntity(baseUrl() + "/api/v1/workspace/new", req, Map.class);
            log.info("Created AnythingLLM workspace for tenant: {}", tenantSlug);
        } catch (Exception e) {
            log.warn("Failed to create AnythingLLM workspace for {}: {}", tenantSlug, e.getMessage());
        }
        return tenantSlug;
    }

    public String pushTextDocument(String tenantSlug, String title, String text) {
        if (!isConfigured()) return null;
        try {
            getOrCreateWorkspace(tenantSlug);
            Map<String, Object> body = Map.of(
                "textContent", text,
                "metadata", Map.of("title", title, "docAuthor", "pulsar")
            );
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, authHeaders());
            ResponseEntity<Map> resp = rest.postForEntity(
                baseUrl() + "/api/v1/document/raw-text", req, Map.class
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) resp.getBody().get("document");
            String location = doc != null ? (String) doc.get("location") : null;
            if (location != null) embedDocuments(tenantSlug, List.of(location));
            return location;
        } catch (Exception e) {
            log.warn("Failed to push text document to AnythingLLM: {}", e.getMessage());
            return null;
        }
    }

    public String uploadFile(String tenantSlug, String filename, String contentType, byte[] data) {
        if (!isConfigured()) return null;
        try {
            getOrCreateWorkspace(tenantSlug);
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(data) {
                @Override public String getFilename() { return filename; }
            };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(contentType));
            body.add("file", new HttpEntity<>(resource, fileHeaders));
            ResponseEntity<Map> resp = rest.postForEntity(
                baseUrl() + "/api/v1/document/upload",
                new HttpEntity<>(body, headers), Map.class
            );
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) resp.getBody().get("documents");
            String location = (docs != null && !docs.isEmpty()) ? (String) docs.get(0).get("location") : null;
            if (location != null) embedDocuments(tenantSlug, List.of(location));
            return location;
        } catch (Exception e) {
            log.warn("Failed to upload file to AnythingLLM: {}", e.getMessage());
            return null;
        }
    }

    public void removeDocument(String tenantSlug, String docLocation) {
        if (!isConfigured() || docLocation == null) return;
        try {
            Map<String, Object> body = Map.of("adds", List.of(), "deletes", List.of(docLocation));
            rest.exchange(
                baseUrl() + "/api/v1/workspace/" + tenantSlug + "/update-embeddings",
                HttpMethod.POST, new HttpEntity<>(body, authHeaders()), Map.class
            );
        } catch (Exception e) {
            log.warn("Failed to remove document from AnythingLLM: {}", e.getMessage());
        }
    }

    public String chat(String tenantSlug, String message) {
        if (!isConfigured()) return "LLM not configured. Set ANYTHINGLLM_API_KEY to enable chat.";
        try {
            getOrCreateWorkspace(tenantSlug);
            Map<String, String> body = Map.of("message", message, "mode", "chat");
            HttpEntity<Map<String, String>> req = new HttpEntity<>(body, authHeaders());
            ResponseEntity<Map> resp = rest.postForEntity(
                baseUrl() + "/api/v1/workspace/" + tenantSlug + "/chat",
                req, Map.class
            );
            return (String) resp.getBody().get("textResponse");
        } catch (Exception e) {
            log.warn("AnythingLLM chat error: {}", e.getMessage());
            return "Chat unavailable: " + e.getMessage();
        }
    }

    private void embedDocuments(String tenantSlug, List<String> locations) {
        try {
            Map<String, Object> body = Map.of("adds", locations, "deletes", List.of());
            rest.exchange(
                baseUrl() + "/api/v1/workspace/" + tenantSlug + "/update-embeddings",
                HttpMethod.POST, new HttpEntity<>(body, authHeaders()), Map.class
            );
        } catch (Exception e) {
            log.warn("Failed to embed documents in AnythingLLM: {}", e.getMessage());
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(apiKey());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
