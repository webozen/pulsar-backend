package com.pulsar.voice.ringcentral.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.voice.ringcentral.RingCentralTokenService;
import java.io.IOException;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side proxy for the RingCentral WebPhone SDK's SIP provisioning call.
 * The browser SDK can't hit RC's {@code /restapi/v1.0/client-info/sip-provision}
 * directly because that endpoint requires the tenant's OAuth bearer (we keep
 * it server-side); so we proxy with the stored token.
 *
 * <p>Returned JSON is passed verbatim to the SDK's {@code RingCentralWebPhone}
 * constructor in the browser. No transformation needed.
 */
@RestController
@RequestMapping("/api/voice/ringcentral/webphone")
@RequireModule("voice-ringcentral")
public class RingCentralWebPhoneController {

    private static final String RC_BASE = "https://platform.ringcentral.com";
    private static final okhttp3.MediaType FORM = okhttp3.MediaType.get("application/x-www-form-urlencoded");

    private final TenantDataSources tenantDs;
    private final RingCentralTokenService tokenService;
    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public RingCentralWebPhoneController(TenantDataSources tenantDs,
                                         RingCentralTokenService tokenService) {
        this.tenantDs     = tenantDs;
        this.tokenService = tokenService;
    }

    /** Provision a SIP-over-WSS endpoint for the browser SDK. */
    @PostMapping("/sip-provision")
    public ResponseEntity<String> sipProvision() throws IOException {
        var t = TenantContext.require();
        String token = tokenService.getAccessToken(tenantDs.forDb(t.dbName()));
        if (token == null) throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
            "ringcentral_oauth_not_configured");

        // RC requires sipInfo[] to be POSTed with a transport list. WebRTC
        // browsers only support WSS — single-element body covers it.
        RequestBody body = RequestBody.create("sipInfo=%5B%7B%22transport%22%3A%22WSS%22%7D%5D".getBytes(), FORM);
        Request req = new Request.Builder()
            .url(RC_BASE + "/restapi/v1.0/client-info/sip-provision")
            .addHeader("Authorization", "Bearer " + token)
            .addHeader("Accept", "application/json")
            .post(body)
            .build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "rc_sip_provision_" + resp.code() + ": " + safe(text));
            }
            return ResponseEntity.ok().header("Content-Type", "application/json").body(text);
        }
    }

    /** Tell the frontend whether SDK mode is on (and any extra options). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        var t = TenantContext.require();
        var rows = new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT use_web_phone_sdk, oauth_access_token IS NOT NULL AS has_oauth " +
            "FROM voice_provider_config WHERE provider_id = 'ringcentral'"
        );
        if (rows.isEmpty()) return Map.of("enabled", false, "configured", false);
        var r = rows.get(0);
        Object f = r.get("use_web_phone_sdk");
        boolean enabled = f instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(f);
        Object h = r.get("has_oauth");
        boolean configured = h instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(h);
        return Map.of("enabled", enabled, "configured", configured);
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 240 ? s.substring(0, 240) + "…" : s;
    }
}
