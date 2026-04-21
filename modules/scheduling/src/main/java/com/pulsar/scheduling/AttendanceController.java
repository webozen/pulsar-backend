package com.pulsar.scheduling;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/office/attendance")
@RequireModule("scheduling")
public class AttendanceController {

    private final TenantDataSources tenantDs;

    public AttendanceController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record AttendanceRecord(
        long staffId,
        String attendanceDate,
        String clockIn,
        String clockOut,
        String notes
    ) {}

    public record BulkRequest(List<AttendanceRecord> attendanceRecords) {}

    @GetMapping("/{staffId}")
    public List<Map<String, Object>> getForEmployee(@PathVariable long staffId) {
        return jdbc().queryForList(
            "SELECT * FROM staff_attendance WHERE staff_id = ? ORDER BY attendance_date DESC", staffId);
    }

    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> bulkSave(@RequestBody BulkRequest req) {
        JdbcTemplate jdbc = jdbc();
        int ok = 0, err = 0;
        for (AttendanceRecord r : req.attendanceRecords()) {
            java.sql.Date date;
            java.sql.Time clockIn, clockOut;
            try {
                // Per-record input validation. Malformed date/time = count as error and move on;
                // everything else (DB down, constraint violation) must propagate so the caller
                // can tell the difference between "1 bad record" and "whole batch failed".
                date = java.sql.Date.valueOf(r.attendanceDate());
                clockIn = parseTime(r.clockIn());
                clockOut = parseTime(r.clockOut());
            } catch (IllegalArgumentException e) {
                err++;
                continue;
            }
            jdbc.update(
                "INSERT INTO staff_attendance (staff_id, attendance_date, clock_in, clock_out, notes) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE clock_in=VALUES(clock_in), clock_out=VALUES(clock_out), notes=VALUES(notes)",
                r.staffId(), date, clockIn, clockOut, r.notes()
            );
            ok++;
        }
        return Map.of("success", true, "successCount", ok, "errorCount", err);
    }

    /** Strict HH:mm parsing — matches the regex used by the onboarding controller so both
     *  endpoints reject the same malformed input. Time.valueOf alone is too lenient. */
    private static java.sql.Time parseTime(String hhmm) {
        if (hhmm == null) return null;
        if (!hhmm.matches("^\\d{2}:\\d{2}$")) {
            throw new IllegalArgumentException("time must be HH:mm, got: " + hhmm);
        }
        return java.sql.Time.valueOf(hhmm + ":00");
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
