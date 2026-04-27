package com.pulsar.automationsync.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Posts tenant lifecycle events to the flow-platform's
 * {@code /api/automation/tenant-sync/*} endpoints.
 *
 * <p>Failures don't throw — every public method returns {@link Result} which
 * captures success/failure plus the marshalled JSON payload so the audit
 * service can re-queue the call later. This way a tenant create or module
 * update in Pulsar is never blocked by a flow-platform outage.
 */
@Component
public class AutomationPlatformClient {

    private static final Logger log = LoggerFactory.getLogger(AutomationPlatformClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String secret;

    public AutomationPlatformClient(
        @Value("${pulsar.automation.base-url:http://localhost:3002}") String baseUrl,
        @Value("${pulsar.automation.sync-secret:}") String secret
    ) {
        // Strip trailing slashes so paths concatenate cleanly.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.secret  = secret == null ? "" : secret;
    }

    public record Result(boolean ok, int status, String body, String requestPath, String requestJson, String error) {
        public boolean shouldRetry() { return !ok; }
    }

    public Result provision(String slug, String name, String contactEmail, Collection<String> modules) {
        return post("/api/automation/tenant-sync/provision",
            Map.of("slug", slug, "name", name == null ? "" : name,
                   "contactEmail", contactEmail == null ? "" : contactEmail,
                   "modules", modules));
    }

    public Result updateModules(String slug, Collection<String> modules) {
        return post("/api/automation/tenant-sync/update-modules",
            Map.of("slug", slug, "modules", modules));
    }

    public Result suspend(String slug) {
        return post("/api/automation/tenant-sync/suspend", Map.of("slug", slug));
    }

    public Result resume(String slug, Collection<String> modules) {
        return post("/api/automation/tenant-sync/resume",
            Map.of("slug", slug, "modules", modules == null ? java.util.List.of() : modules));
    }

    public Result delete(String slug) {
        return post("/api/automation/tenant-sync/delete", Map.of("slug", slug));
    }

    public Result pushSecrets(String slug, Map<String, String> secrets) {
        return post("/api/automation/tenant-sync/secrets",
            Map.of("slug", slug, "secrets", secrets));
    }

    /** Used by the admin status panel — synchronous, no audit fallback. */
    public Result status(String slug) {
        return get("/api/automation/tenant-sync/status?slug=" + urlEncode(slug));
    }

    /** Replays a previously-failed call from the audit table. The body has
     *  already been marshalled, so we don't go through the typed methods. */
    public Result replay(String path, String json) {
        if (secret.isBlank()) {
            return new Result(false, 0, null, path, json, "no_secret_configured");
        }
        Request req = new Request.Builder()
            .url(baseUrl + path)
            .addHeader("X-Pulsar-Sync-Secret", secret)
            .post(RequestBody.create(json.getBytes(StandardCharsets.UTF_8), JSON))
            .build();
        return execute(req, path, json);
    }

    // ───────────────────────────────────────────────────────────────────────

    private Result post(String path, Map<String, ?> body) {
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (IOException e) { return new Result(false, 0, null, path, "", "marshal: " + e.getMessage()); }

        if (secret.isBlank()) {
            log.warn("automation sync skipped (path={}): PULSAR_AUTOMATION_SYNC_SECRET unset", path);
            return new Result(false, 0, null, path, json, "no_secret_configured");
        }

        Request req = new Request.Builder()
            .url(baseUrl + path)
            .addHeader("X-Pulsar-Sync-Secret", secret)
            .post(RequestBody.create(json.getBytes(StandardCharsets.UTF_8), JSON))
            .build();
        return execute(req, path, json);
    }

    private Result get(String path) {
        if (secret.isBlank()) {
            return new Result(false, 0, null, path, "", "no_secret_configured");
        }
        Request req = new Request.Builder()
            .url(baseUrl + path)
            .addHeader("X-Pulsar-Sync-Secret", secret)
            .get()
            .build();
        return execute(req, path, "");
    }

    private Result execute(Request req, String path, String json) {
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            boolean ok = resp.isSuccessful();
            if (!ok) {
                log.warn("automation sync non-2xx: path={} status={} body_preview={}",
                    path, resp.code(), preview(body));
            }
            return new Result(ok, resp.code(), body, path, json, ok ? null : "http_" + resp.code());
        } catch (IOException e) {
            log.warn("automation sync failed (will audit): path={} err={}", path, e.getMessage());
            return new Result(false, 0, null, path, json, "io: " + e.getMessage());
        }
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
