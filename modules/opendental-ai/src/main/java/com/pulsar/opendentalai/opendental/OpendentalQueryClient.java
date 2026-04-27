package com.pulsar.opendentalai.opendental;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Thin client for the OpenDental Query API described at
 * {@code https://www.opendental.com/site/apiqueries.html}.
 *
 * <p>Authenticates with per-customer DeveloperKey + CustomerKey; executes a
 * read-only SELECT against that customer's local Open Dental database.
 * Errors (HTTP >= 400 or invalid JSON) throw {@link OdQueryException} — the
 * caller decides whether to surface the message to the end user or just log.
 */
@Component
public class OpendentalQueryClient {

    private static final String DEFAULT_ENDPOINT =
        "https://api.opendental.com/api/v1/queries/ShortQuery";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public record QueryRequest(String developerKey, String customerKey, String sql) {}

    public record QueryResult(List<Map<String, Object>> rows, int rowCount) {}

    public static class OdQueryException extends RuntimeException {
        public OdQueryException(String msg) { super(msg); }
    }

    public QueryResult run(QueryRequest req) throws IOException {
        String endpoint = System.getenv().getOrDefault("OPENDENTAL_QUERY_ENDPOINT", DEFAULT_ENDPOINT);
        String body = mapper.writeValueAsString(Map.of("SqlCommand", req.sql()));

        // OpenDental's Query API rejects a Content-Type with any parameter (e.g.
        // "application/json; charset=utf-8"). OkHttp's String-body factory appends
        // "; charset=utf-8" when the MediaType has no charset, so construct the
        // body from raw UTF-8 bytes to keep the header exactly "application/json".
        Request httpReq = new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "ODFHIR " + req.developerKey() + "/" + req.customerKey())
            .addHeader("Accept", "application/json")
            .put(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
            .build();

        try (Response resp = http.newCall(httpReq).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new OdQueryException("OpenDental API " + resp.code() + ": " + safePreview(text));
            }
            // OD's ShortQuery returns an array of row objects (property names = SQL column aliases).
            List<Map<String, Object>> rows = mapper.readValue(text, new TypeReference<>() {});
            return new QueryResult(rows, rows.size());
        }
    }

    private static String safePreview(String s) {
        if (s == null) return "";
        return s.length() > 240 ? s.substring(0, 240) + "…" : s;
    }
}
