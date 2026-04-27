package com.pulsar.kernel.voice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.voice.VoiceProviderRegistry;
import com.pulsar.kernel.voice.VoiceWebhookAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VoiceWebhookControllerTest {

    private TenantLookupService tenantLookup;
    private TenantDataSources tenantDs;
    private VoiceProviderRegistry registry;
    private ApplicationEventPublisher eventPublisher;
    private ObjectMapper mapper;
    private VoiceWebhookController controller;

    @BeforeEach
    void setUp() {
        tenantLookup = mock(TenantLookupService.class);
        tenantDs = mock(TenantDataSources.class);
        registry = mock(VoiceProviderRegistry.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        mapper = new ObjectMapper();
        controller = new VoiceWebhookController(tenantLookup, tenantDs, registry, eventPublisher, mapper);
    }

    /**
     * Regression for: RingCentral subscription-create handshake fails with
     * SUB-522 because RC POSTs an empty body and Spring rejects the request
     * with 415 before our handler can echo the Validation-Token. The
     * @RequestBody must be optional so the validation handshake works.
     */
    @Test
    void emptyBodyValidationHandshakeReturnsEcho() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Validation-Token")).thenReturn("rc-validation-12345");
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

        ResponseEntity<Map<String, Object>> resp = controller.webhook(
            "acme-dental", "ringcentral", null /* no body */, req);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("rc-validation-12345", resp.getHeaders().getFirst("Validation-Token"));
        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
    }

    @Test
    void unknownTenantReturns404() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Validation-Token")).thenReturn(null);
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(tenantLookup.bySlug("ghost")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.webhook(
            "ghost", "ringcentral", Map.of(), req);

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void unknownProviderReturns404() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Validation-Token")).thenReturn(null);
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(tenantLookup.bySlug("acme-dental"))
            .thenReturn(Optional.of(new TenantRecord(
                1L, "acme-dental", "Acme", "acme_db",
                java.util.Set.of(), "owner@acme.test", "hash", null, null, null)));
        when(tenantDs.forDb("acme_db")).thenReturn(mock(DataSource.class));
        when(registry.webhook("unknown")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.webhook(
            "acme-dental", "unknown", Map.of(), req);

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void invalidSignatureReturns401() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Validation-Token")).thenReturn(null);
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(tenantLookup.bySlug("acme-dental"))
            .thenReturn(Optional.of(new TenantRecord(
                1L, "acme-dental", "Acme", "acme_db",
                java.util.Set.of(), "owner@acme.test", "hash", null, null, null)));
        when(tenantDs.forDb("acme_db")).thenReturn(mock(DataSource.class));

        VoiceWebhookAdapter adapter = mock(VoiceWebhookAdapter.class);
        when(adapter.validateSignature(any(), any(), any())).thenReturn(false);
        when(registry.webhook("ringcentral")).thenReturn(Optional.of(adapter));

        ResponseEntity<Map<String, Object>> resp = controller.webhook(
            "acme-dental", "ringcentral", Map.of("body", "garbage"), req);

        assertEquals(401, resp.getStatusCode().value());
        assertEquals("invalid_signature", resp.getBody().get("error"));
    }
}
