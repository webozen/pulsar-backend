package com.pulsar.kernel.voice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.voice.CallEndedEvent;
import com.pulsar.kernel.voice.CallEvent;
import com.pulsar.kernel.voice.CallRingingEvent;
import com.pulsar.kernel.voice.VoiceProviderRegistry;
import com.pulsar.kernel.voice.VoiceWebhookAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kernel-level voice webhook receiver keyed by tenant slug.
 *
 * <p>NOT annotated with {@code @RequireModule} — RC sends no JWT. Auth is
 * handled by provider-specific signature validation inside the adapter.
 *
 * <p>Tenant is resolved via slug in the URL path; no JWT is required.
 * On RINGING a {@link CallRingingEvent} is published so the caller-match module
 * can push an SSE screen-pop to the browser.
 * On ENDED a {@link CallEndedEvent} is published so the call-intel module can
 * queue the payload for processing.
 */
@RestController
@RequestMapping("/api/t/{slug}/voice")
public class VoiceWebhookController {

    private final TenantLookupService tenantLookup;
    private final TenantDataSources tenantDs;
    private final VoiceProviderRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper;

    public VoiceWebhookController(
        TenantLookupService tenantLookup,
        TenantDataSources tenantDs,
        VoiceProviderRegistry registry,
        ApplicationEventPublisher eventPublisher,
        ObjectMapper mapper
    ) {
        this.tenantLookup = tenantLookup;
        this.tenantDs = tenantDs;
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
    }

    @PostMapping("/webhook/{providerId}")
    public ResponseEntity<Map<String, Object>> webhook(
        @PathVariable String slug,
        @PathVariable String providerId,
        @RequestBody(required = false) Map<String, Object> payloadOrNull,
        HttpServletRequest httpReq
    ) {
        // 1. RC validation-token handshake — echo back and return 200 immediately.
        // RC sends an empty-body POST during subscription create; @RequestBody is
        // optional so Spring doesn't 415 us before we can echo the token.
        String validation = httpReq.getHeader("Validation-Token");
        if (validation != null) {
            return ResponseEntity.ok()
                .header("Validation-Token", validation)
                .body(Map.of("ok", true));
        }
        Map<String, Object> payload = payloadOrNull == null ? Collections.emptyMap() : payloadOrNull;

        // 2. Look up tenant by slug
        TenantRecord tenant = tenantLookup.bySlug(slug).orElse(null);
        if (tenant == null) {
            return ResponseEntity.notFound().build();
        }

        // 3. Get DataSource for tenant
        DataSource ds = tenantDs.forDb(tenant.dbName());

        // 4. Get adapter for provider
        VoiceWebhookAdapter adapter = registry.webhook(providerId).orElse(null);
        if (adapter == null) {
            return ResponseEntity.notFound().build();
        }

        // 5. Validate signature
        Map<String, String> headers = headerMap(httpReq);
        if (!adapter.validateSignature(payload, headers, ds)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

        // 6. Parse event
        var eventOpt = adapter.parse(payload, headers);
        if (eventOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("ignored", true));
        }
        CallEvent event = eventOpt.get();

        // 7. Serialize raw payload for ended event
        String rawJson;
        try {
            rawJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            rawJson = "{}";
        }

        // 8. Dispatch by event type
        switch (event.eventType()) {
            case RINGING -> eventPublisher.publishEvent(new CallRingingEvent(
                slug,
                tenant.dbName(),
                event.fromPhone(),
                event.sessionId(),
                event.direction() != null ? event.direction().name() : "UNKNOWN"
            ));
            case ENDED -> eventPublisher.publishEvent(new CallEndedEvent(
                slug,
                tenant.dbName(),
                providerId,
                rawJson
            ));
            default -> { /* CONNECTED and others ignored */ }
        }

        // 9. Return 200 queued
        return ResponseEntity.ok(Map.of("queued", true));
    }

    private static Map<String, String> headerMap(HttpServletRequest req) {
        var names = req.getHeaderNames();
        if (names == null) return Collections.emptyMap();
        return Collections.list(names).stream()
            .collect(Collectors.toMap(n -> n, req::getHeader, (a, b) -> a));
    }
}
