package com.pulsar.voice.ringcentral;

import com.pulsar.kernel.voice.RecordingFetcher;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Fetches a recording from RingCentral's media URL. RC returns either:
 *
 * <ul>
 *   <li>An absolute https://media.ringcentral.com/... URL → use as-is.</li>
 *   <li>A bare numeric id → resolve to
 *       {@code https://platform.ringcentral.com/restapi/v1.0/account/~/recording/{id}/content}.</li>
 * </ul>
 *
 * Authenticated with a Bearer token from voice_provider_config.oauth_access_token.
 * We do NOT refresh expired tokens here — that's the responsibility of an
 * outer scheduled task (separate ticket); on 401 we throw and the caller
 * surfaces an error via the call-intel webhook queue.
 */
@Component
public class RingCentralRecordingFetcher implements RecordingFetcher {

    private static final String RC_RECORDING_BASE =
        "https://platform.ringcentral.com/restapi/v1.0/account/~/recording/";

    private final RingCentralTokenService tokenService;

    public RingCentralRecordingFetcher(RingCentralTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override public String id() { return "ringcentral"; }
    @Override public String defaultMimeType() { return "audio/mpeg"; }

    @Override
    public byte[] fetch(String recordingRef, DataSource tenantDs) throws IOException {
        String token = tokenService.getAccessToken(tenantDs);
        if (token == null || token.isBlank()) {
            throw new IOException("ringcentral_oauth_not_configured");
        }
        String url = recordingRef.startsWith("http") ? recordingRef
            : RC_RECORDING_BASE + recordingRef + "/content";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "audio/mpeg, audio/wav, application/octet-stream, */*");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new IOException("ringcentral_recording_fetch_" + status);
        }
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

}
