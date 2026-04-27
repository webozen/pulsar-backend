package com.pulsar.copilot.api;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoints for the Co-Pilot UI. The WebSocket {@code /ws/copilot}
 * is where the actual streaming happens; this controller serves the session
 * history rail and per-session detail.
 */
@RestController
@RequestMapping("/api/copilot")
@RequireModule("call-handling")
public class CoPilotController {

    private final TenantDataSources tenantDs;

    public CoPilotController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        var t = TenantContext.require();
        return new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT id, provider_id, provider_session_id, started_at, ended_at, " +
            "       JSON_LENGTH(suggestions) AS suggestion_count " +
            "FROM copilot_session_log ORDER BY started_at DESC LIMIT 50"
        );
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable long id) {
        var t = TenantContext.require();
        var rows = new JdbcTemplate(tenantDs.forDb(t.dbName())).queryForList(
            "SELECT * FROM copilot_session_log WHERE id = ?", id
        );
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
}
