package com.pulsar.host.api.tenant;

import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.credentials.OpenDentalKeyResolver;
import com.pulsar.kernel.credentials.PlaudKeyResolver;
import com.pulsar.kernel.credentials.TwilioCredentialsResolver;
import com.pulsar.kernel.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tenant-self-serve credentials endpoint. Mirrors the surface of
 * {@link com.pulsar.host.api.admin.AdminApiKeysController} but scopes to the
 * caller's own tenant via the JWT and {@link TenantContext}.
 */
@RestController
@RequestMapping("/api/tenant/credentials")
public class TenantCredentialsController {

    private final GeminiKeyResolver geminiKeyResolver;
    private final OpenDentalKeyResolver opendentalKeyResolver;
    private final TwilioCredentialsResolver twilioResolver;
    private final PlaudKeyResolver plaudResolver;

    public TenantCredentialsController(
        GeminiKeyResolver geminiKeyResolver,
        OpenDentalKeyResolver opendentalKeyResolver,
        TwilioCredentialsResolver twilioResolver,
        PlaudKeyResolver plaudResolver
    ) {
        this.geminiKeyResolver = geminiKeyResolver;
        this.opendentalKeyResolver = opendentalKeyResolver;
        this.twilioResolver = twilioResolver;
        this.plaudResolver = plaudResolver;
    }

    public record GeminiKeyRequest(String apiKey, Boolean useDefault) {}
    public record OpenDentalKeysRequest(String developerKey, String customerKey) {}
    public record TwilioCredsRequest(String accountSid, String authToken, String fromNumber) {}
    public record PlaudKeyRequest(String bearerToken) {}

    @GetMapping
    public Map<String, Object> get() {
        var t = TenantContext.require();
        return buildStatusResponse(t.dbName());
    }

    @PutMapping("/gemini")
    public Map<String, Object> updateGemini(@RequestBody GeminiKeyRequest req) {
        var actor = requireTenantActor();
        var t = TenantContext.require();
        String apiKey = req.apiKey() == null ? null : req.apiKey().trim();
        geminiKeyResolver.updateForDb(t.dbName(), apiKey, req.useDefault(), actor.email(), actor.role());
        return buildStatusResponse(t.dbName());
    }

    @PutMapping("/opendental")
    public Map<String, Object> updateOpenDental(@RequestBody OpenDentalKeysRequest req) {
        var actor = requireTenantActor();
        var t = TenantContext.require();
        opendentalKeyResolver.update(
            t.dbName(),
            req.developerKey() == null ? null : req.developerKey().trim(),
            req.customerKey()  == null ? null : req.customerKey().trim(),
            actor.email(), actor.role()
        );
        return buildStatusResponse(t.dbName());
    }

    @PutMapping("/twilio")
    public Map<String, Object> updateTwilio(@RequestBody TwilioCredsRequest req) {
        var actor = requireTenantActor();
        var t = TenantContext.require();
        twilioResolver.update(
            t.dbName(),
            req.accountSid() == null ? null : req.accountSid().trim(),
            req.authToken(), // blank means "leave untouched"
            req.fromNumber() == null ? null : req.fromNumber().trim(),
            actor.email(), actor.role()
        );
        return buildStatusResponse(t.dbName());
    }

    @PutMapping("/plaud")
    public Map<String, Object> updatePlaud(@RequestBody PlaudKeyRequest req) {
        var actor = requireTenantActor();
        var t = TenantContext.require();
        plaudResolver.update(
            t.dbName(),
            req.bearerToken() == null ? null : req.bearerToken().trim(),
            actor.email(), actor.role()
        );
        return buildStatusResponse(t.dbName());
    }

    private Principal.TenantUser requireTenantActor() {
        Principal p = PrincipalContext.get();
        if (!(p instanceof Principal.TenantUser tu)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_user_required");
        }
        var t = TenantContext.require();
        if (!tu.slug().equals(t.slug())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_mismatch");
        }
        return tu;
    }

    private Map<String, Object> buildStatusResponse(String dbName) {
        Map<String, Object> gemini = new LinkedHashMap<>();
        var gStatus = geminiKeyResolver.statusForDb(dbName);
        gemini.put("hasTenantKey", gStatus.hasTenantKey());
        gemini.put("useDefault", gStatus.useDefault());
        gemini.put("defaultAvailable", geminiKeyResolver.platformDefaultAvailable());

        Map<String, Object> opendental = new LinkedHashMap<>();
        var odStatus = opendentalKeyResolver.statusForDb(dbName);
        opendental.put("hasDeveloperKey", odStatus.hasDeveloperKey());
        opendental.put("hasCustomerKey", odStatus.hasCustomerKey());

        Map<String, Object> twilio = new LinkedHashMap<>();
        var tStatus = twilioResolver.statusForDb(dbName);
        twilio.put("hasAccountSid", tStatus.hasAccountSid());
        twilio.put("hasAuthToken", tStatus.hasAuthToken());
        twilio.put("hasFromNumber", tStatus.hasFromNumber());

        Map<String, Object> plaud = new LinkedHashMap<>();
        plaud.put("hasToken", plaudResolver.statusForDb(dbName).hasToken());

        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("gemini", gemini);
        providers.put("opendental", opendental);
        providers.put("twilio", twilio);
        providers.put("plaud", plaud);
        resp.put("providers", providers);
        return resp;
    }
}
