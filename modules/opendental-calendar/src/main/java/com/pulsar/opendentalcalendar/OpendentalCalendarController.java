package com.pulsar.opendentalcalendar;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/opendental-calendar")
@RequireModule("opendental-calendar")
public class OpendentalCalendarController {

    private final TenantDataSources tenantDs;
    private final OdCalendarQueryClient client;

    public OpendentalCalendarController(TenantDataSources tenantDs, OdCalendarQueryClient client) {
        this.tenantDs = tenantDs;
        this.client = client;
    }

    public record ConfigRequest(
        @NotBlank String odDeveloperKey,
        @NotBlank String odCustomerKey
    ) {}

    @GetMapping("/config")
    public Map<String, Object> configStatus() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        var rows = jdbc.queryForList("SELECT 1 FROM opendental_calendar_config WHERE id = 1");
        return Map.of("onboarded", !rows.isEmpty());
    }

    @PostMapping("/config")
    public Map<String, Object> saveConfig(@Valid @RequestBody ConfigRequest req) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        jdbc.update(
            "INSERT INTO opendental_calendar_config (id, od_developer_key, od_customer_key) " +
            "VALUES (1, ?, ?) " +
            "ON DUPLICATE KEY UPDATE od_developer_key = VALUES(od_developer_key), " +
            "od_customer_key = VALUES(od_customer_key)",
            req.odDeveloperKey(), req.odCustomerKey()
        );
        return Map.of("onboarded", true);
    }

    @GetMapping("/operatories")
    public List<Map<String, Object>> operatories() {
        var keys = loadKeys();
        try {
            return client.query(keys.devKey(), keys.custKey(),
                "SELECT OperatoryNum, OpName, Abbrev FROM operatory " +
                "WHERE IsHidden = 0 ORDER BY ItemOrder");
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/appointments")
    public List<Map<String, Object>> appointments(@RequestParam String date) {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD");
        }
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid date: " + date);
        }
        // Safe to concatenate: value is validated to YYYY-MM-DD above (only digits + hyphens,
        // logically valid date) — no SQL metacharacter can appear in this pattern.
        var keys = loadKeys();
        String sql =
            "SELECT a.AptNum, a.PatNum, a.AptDateTime, a.AptTimeEnd, a.Op, " +
            "       a.ProcDescript, a.AptStatus, a.IsNewPatient, " +
            "       p.FName, p.LName, p.HmPhone, p.WirelessPhone, " +
            "       o.OpName, o.Abbrev AS OpAbbrev, " +
            "       prov.Abbrev AS ProvAbbr " +
            "FROM appointment a " +
            "JOIN patient p ON a.PatNum = p.PatNum " +
            "JOIN operatory o ON a.Op = o.OperatoryNum " +
            "JOIN provider prov ON a.ProvNum = prov.ProvNum " +
            "WHERE DATE(a.AptDateTime) = '" + date + "' " +
            "  AND a.AptStatus = 1 " +
            "ORDER BY a.AptDateTime";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    private record Keys(String devKey, String custKey) {}

    private Keys loadKeys() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        var rows = jdbc.queryForList(
            "SELECT od_developer_key, od_customer_key FROM opendental_calendar_config WHERE id = 1");
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Module not configured");
        }
        var row = rows.get(0);
        return new Keys((String) row.get("od_developer_key"), (String) row.get("od_customer_key"));
    }
}
