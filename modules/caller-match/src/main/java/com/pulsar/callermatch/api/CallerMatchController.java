package com.pulsar.callermatch.api;

import com.pulsar.callermatch.CallerMatchEventService;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Lookup a caller phone number in the tenant's OpenDental database and return
 * a patient summary card for the front-desk UI. Also exposes an SSE endpoint
 * that pushes screen-pop events to browser tabs when the office phone rings.
 */
@RestController
@RequestMapping("/api/caller-match")
@RequireModule("call-handling")
public class CallerMatchController {

    private final TenantDataSources tenantDs;
    private final CallerMatchEventService eventService;

    public CallerMatchController(TenantDataSources tenantDs, CallerMatchEventService eventService) {
        this.tenantDs = tenantDs;
        this.eventService = eventService;
    }

    /** Quick lookup without logging — used for hover/preview in the UI. */
    @GetMapping("/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@RequestParam String phone) {
        var t = TenantContext.require();
        return ResponseEntity.ok(eventService.buildCard(phone, t.dbName()));
    }

    /** Last 50 screen-pops, newest first — powers the "Recent calls" panel. */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent() {
        var t = TenantContext.require();
        return new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT id, phone, direction, provider_id, provider_session_id, caller_name, " +
            "       matched_patnum, matched_patient_name, matched_at " +
            "FROM caller_match_log ORDER BY matched_at DESC LIMIT 50"
        );
    }

    /**
     * SSE subscription endpoint. Browser tabs connect here to receive real-time
     * screen-pop events when the office phone rings.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        var t = TenantContext.require();
        return eventService.subscribe(t.dbName());
    }
}
