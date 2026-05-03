package com.pulsar.host.api.admin;

import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.credentials.OpenDentalKeyResolver;
import com.pulsar.kernel.credentials.PlaudKeyResolver;
import com.pulsar.kernel.credentials.TwilioCredentialsResolver;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-tenant credential management for the super-admin UI. The actual
 * credential values are never returned — only presence flags. UI shows
 * cross-tenant scope (any tenant); the parallel
 * {@link com.pulsar.host.api.tenant.TenantCredentialsController} provides
 * the same surface scoped to the JWT's tenant for self-serve.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}/api-keys")
public class AdminApiKeysController {

    private final TenantRepository tenantRepo;
    private final GeminiKeyResolver geminiKeyResolver;
    private final OpenDentalKeyResolver opendentalKeyResolver;
    private final TwilioCredentialsResolver twilioResolver;
    private final PlaudKeyResolver plaudResolver;

    public AdminApiKeysController(
        TenantRepository tenantRepo,
        GeminiKeyResolver geminiKeyResolver,
        OpenDentalKeyResolver opendentalKeyResolver,
        TwilioCredentialsResolver twilioResolver,
        PlaudKeyResolver plaudResolver
    ) {
        this.tenantRepo = tenantRepo;
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
    public Map<String, Object> get(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        return buildStatusResponse(tenant.dbName());
    }

    @PutMapping("/gemini")
    public Map<String, Object> updateGemini(@PathVariable long id, @RequestBody GeminiKeyRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        String apiKey = req.apiKey() == null ? null : req.apiKey().trim();
        geminiKeyResolver.updateForDb(tenant.dbName(), apiKey, req.useDefault());
        return buildStatusResponse(tenant.dbName());
    }

    @PutMapping("/opendental")
    public Map<String, Object> updateOpenDental(@PathVariable long id, @RequestBody OpenDentalKeysRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        opendentalKeyResolver.update(
            tenant.dbName(),
            req.developerKey() == null ? null : req.developerKey().trim(),
            req.customerKey()  == null ? null : req.customerKey().trim(),
            null, "super_admin"
        );
        return buildStatusResponse(tenant.dbName());
    }

    @PutMapping("/twilio")
    public Map<String, Object> updateTwilio(@PathVariable long id, @RequestBody TwilioCredsRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        twilioResolver.update(
            tenant.dbName(),
            req.accountSid() == null ? null : req.accountSid().trim(),
            // authToken: null/blank means "leave untouched" (per resolver contract)
            req.authToken(),
            req.fromNumber() == null ? null : req.fromNumber().trim(),
            null, "super_admin"
        );
        return buildStatusResponse(tenant.dbName());
    }

    /** Wipe all three Twilio fields in one call. The PUT path can't clear
     *  the auth token because blank-means-untouched there, so a destructive
     *  "Clear all" lives on its own DELETE endpoint. */
    @DeleteMapping("/twilio")
    public Map<String, Object> clearTwilio(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        twilioResolver.clearAll(tenant.dbName(), null, "super_admin");
        return buildStatusResponse(tenant.dbName());
    }

    @PutMapping("/plaud")
    public Map<String, Object> updatePlaud(@PathVariable long id, @RequestBody PlaudKeyRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord tenant = resolveTenant(id);
        plaudResolver.update(
            tenant.dbName(),
            req.bearerToken() == null ? null : req.bearerToken().trim(),
            null, "super_admin"
        );
        return buildStatusResponse(tenant.dbName());
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

    private TenantRecord resolveTenant(long id) {
        return tenantRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant_not_found"));
    }
}
