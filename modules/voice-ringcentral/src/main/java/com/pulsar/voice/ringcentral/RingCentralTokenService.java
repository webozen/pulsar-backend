package com.pulsar.voice.ringcentral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Centralises RingCentral OAuth token access with automatic refresh.
 *
 * <p>Reads the stored OAuth fields from {@code voice_provider_config}, and if
 * the access token is expired (or within 5 minutes of expiry) and client
 * credentials are present, performs a refresh-token grant, rotates both tokens
 * in the DB, and returns the new access token. RC always issues a new refresh
 * token on each use, so both {@code oauth_access_token} and
 * {@code oauth_refresh_token} are updated.
 */
@Component
public class RingCentralTokenService {

    private static final String RC_TOKEN_URL =
        "https://platform.ringcentral.com/restapi/oauth/token";

    /** Tokens expiring within this window are treated as already expired. */
    private static final long EXPIRY_BUFFER_SECONDS = 300L;

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public RingCentralTokenService() {
        this(new OkHttpClient(), new ObjectMapper());
    }

    /** Package-private constructor for testing — allows injection of mocked collaborators. */
    RingCentralTokenService(OkHttpClient http, ObjectMapper mapper) {
        this.http   = http;
        this.mapper = mapper;
    }

    /**
     * Returns a valid access token for the given tenant datasource.
     *
     * <ul>
     *   <li>Returns {@code null} if OAuth is not configured (no row, or blank token).</li>
     *   <li>Returns the stored token immediately if it is not near expiry.</li>
     *   <li>Attempts a refresh if client credentials + refresh token are present
     *       and the token is expired or within {@value EXPIRY_BUFFER_SECONDS} seconds
     *       of expiry.</li>
     *   <li>Falls back to the (possibly expired) stored token if refresh is
     *       not possible due to missing credentials.</li>
     * </ul>
     */
    public String getAccessToken(DataSource tenantDs) {
        TokenRow row = readTokenRow(tenantDs);
        if (row == null) return null;

        String accessToken = row.oauthAccessToken();
        if (accessToken == null || accessToken.isBlank()) return null;

        // If no expiry recorded, assume the token is still valid.
        if (row.oauthExpiresAt() == null) return accessToken;

        boolean nearExpiry = row.oauthExpiresAt().minusSeconds(EXPIRY_BUFFER_SECONDS)
            .isBefore(Instant.now());
        if (!nearExpiry) return accessToken;

        // Token is expired (or nearly so). Attempt a refresh if we have credentials.
        boolean canRefresh = isPresent(row.rcClientId())
            && isPresent(row.rcClientSecret())
            && isPresent(row.oauthRefreshToken());
        if (!canRefresh) return accessToken;

        return doRefresh(tenantDs, row);
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private record TokenRow(
        String oauthAccessToken,
        String oauthRefreshToken,
        Instant oauthExpiresAt,
        String rcClientId,
        String rcClientSecret
    ) {}

    TokenRow readTokenRow(DataSource ds) {
        List<Map<String, Object>> rows = new JdbcTemplate(ds).queryForList(
            "SELECT oauth_access_token, oauth_refresh_token, oauth_expires_at, " +
            "       rc_client_id, rc_client_secret " +
            "FROM voice_provider_config WHERE provider_id = 'ringcentral'"
        );
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);

        Instant expiresAt = null;
        Object raw = r.get("oauth_expires_at");
        if (raw instanceof java.sql.Timestamp ts) {
            expiresAt = ts.toInstant();
        } else if (raw instanceof java.util.Date d) {
            expiresAt = d.toInstant();
        }

        return new TokenRow(
            (String) r.get("oauth_access_token"),
            (String) r.get("oauth_refresh_token"),
            expiresAt,
            (String) r.get("rc_client_id"),
            (String) r.get("rc_client_secret")
        );
    }

    /**
     * Calls the RC token endpoint, persists the new tokens, and returns the
     * new access token. Returns the old (expired) access token on any error so
     * callers still get a non-null value and produce a meaningful 401 upstream.
     */
    String doRefresh(DataSource ds, TokenRow row) {
        String credentials = Base64.getEncoder().encodeToString(
            (row.rcClientId() + ":" + row.rcClientSecret()).getBytes());

        Request req = new Request.Builder()
            .url(RC_TOKEN_URL)
            .addHeader("Authorization", "Basic " + credentials)
            .post(new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", row.oauthRefreshToken())
                .build())
            .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                // Log at warn level — fall back to existing token.
                return row.oauthAccessToken();
            }
            JsonNode json = mapper.readTree(body);
            String newAccess  = json.path("access_token").asText(null);
            String newRefresh = json.path("refresh_token").asText(null);
            long expiresIn    = json.path("expires_in").asLong(3600L);

            if (newAccess == null || newAccess.isBlank()) return row.oauthAccessToken();

            Instant newExpiry = Instant.now().plusSeconds(expiresIn);
            new JdbcTemplate(ds).update(
                "UPDATE voice_provider_config " +
                "SET oauth_access_token = ?, oauth_refresh_token = ?, oauth_expires_at = ? " +
                "WHERE provider_id = 'ringcentral'",
                newAccess,
                newRefresh != null && !newRefresh.isBlank() ? newRefresh : row.oauthRefreshToken(),
                java.sql.Timestamp.from(newExpiry)
            );
            return newAccess;
        } catch (IOException e) {
            return row.oauthAccessToken();
        }
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
