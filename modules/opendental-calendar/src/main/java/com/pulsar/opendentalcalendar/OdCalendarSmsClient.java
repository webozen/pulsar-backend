package com.pulsar.opendentalcalendar;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
public class OdCalendarSmsClient {

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

    public static class TwilioException extends RuntimeException {
        public TwilioException(String msg) { super(msg); }
    }

    public void send(String accountSid, String authToken, String fromNumber, String toNumber, String body)
            throws IOException {
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";

        FormBody formBody = new FormBody.Builder()
            .add("To", toNumber)
            .add("From", fromNumber)
            .add("Body", body)
            .build();

        Request req = new Request.Builder()
            .url(url)
            .addHeader("Authorization", Credentials.basic(accountSid, authToken))
            .post(formBody)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (resp.code() != 201) {
                throw new TwilioException(text);
            }
        }
    }
}
