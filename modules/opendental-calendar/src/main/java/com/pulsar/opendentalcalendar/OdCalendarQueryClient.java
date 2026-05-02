package com.pulsar.opendentalcalendar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
public class OdCalendarQueryClient {

    private static final String DEFAULT_ENDPOINT =
        "https://api.opendental.com/api/v1/queries/ShortQuery";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public static class OdException extends RuntimeException {
        public OdException(String msg) { super(msg); }
    }

    public List<Map<String, Object>> query(String developerKey, String customerKey, String sql)
            throws IOException {
        String endpoint = System.getenv().getOrDefault("OPENDENTAL_QUERY_ENDPOINT", DEFAULT_ENDPOINT);
        String body = mapper.writeValueAsString(Map.of("SqlCommand", sql));

        Request req = new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "ODFHIR " + developerKey + "/" + customerKey)
            .addHeader("Accept", "application/json")
            .put(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
            .build();

        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new OdException("OpenDental API " + resp.code() + ": " +
                    (text.length() > 240 ? text.substring(0, 240) + "…" : text));
            }
            return mapper.readValue(text, new TypeReference<>() {});
        }
    }
}
