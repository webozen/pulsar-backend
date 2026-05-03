package com.pulsar.opendentalcalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
public class ZoomPhoneSmsClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String TOKEN_URL =
            "https://zoom.us/oauth/token?grant_type=account_credentials&account_id=";
    private static final String SMS_URL =
            "https://api.zoom.us/v2/phone/sms/messages";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public static class ZoomException extends RuntimeException {
        public ZoomException(String msg) { super(msg); }
    }

    String fetchAccessToken(String accountId, String clientId, String clientSecret)
            throws IOException {
        Request req = new Request.Builder()
                .url(TOKEN_URL + accountId)
                .addHeader("Authorization", Credentials.basic(clientId, clientSecret))
                .post(RequestBody.create(new byte[0], null))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                String preview = text.length() > 240 ? text.substring(0, 240) + "…" : text;
                throw new ZoomException("zoom_token_http_" + resp.code() + ": " + preview);
            }
            Map<?, ?> json = mapper.readValue(text, Map.class);
            return (String) json.get("access_token");
        }
    }

    public void send(String accountId, String clientId, String clientSecret,
                     String fromNumber, String toNumber, String body) throws IOException {
        String accessToken = fetchAccessToken(accountId, clientId, clientSecret);

        Map<String, Object> payload = Map.of(
                "from_phone_number", fromNumber,
                "to_members", List.of(Map.of("phone_number", toNumber)),
                "message", body
        );

        String json = mapper.writeValueAsString(payload);

        Request req = new Request.Builder()
                .url(SMS_URL)
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() != 201) {
                String text = resp.body() == null ? "" : resp.body().string();
                String preview = text.length() > 240 ? text.substring(0, 240) + "…" : text;
                throw new ZoomException("zoom_sms_http_" + resp.code() + ": " + preview);
            }
        }
    }
}
