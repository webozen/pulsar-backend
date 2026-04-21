package com.pulsar.scheduling;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/office/shifts")
@RequireModule("scheduling")
public class ShiftsController {

    private final TenantDataSources tenantDs;

    public ShiftsController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record CreateShiftsRequest(
        List<String> dates,
        String startDate,
        String endDate,
        List<Integer> daysOfWeek,
        String status,
        Integer location,
        String notes
    ) {}

    @GetMapping
    public List<Map<String, Object>> list(
        @RequestParam(name = "date", required = false) String date,
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate,
        @RequestParam(name = "employeeId", required = false) Long employeeId
    ) {
        JdbcTemplate jdbc = jdbc();
        if (employeeId != null && startDate != null && endDate != null) {
            return jdbc.queryForList(
                "SELECT s.*, m.first_name, m.last_name, m.position, m.department, m.location as employee_location " +
                "FROM staff_shifts s JOIN staff_members m ON s.staff_id = m.id " +
                "WHERE s.staff_id = ? AND s.shift_date BETWEEN ? AND ? ORDER BY s.shift_date",
                employeeId, startDate, endDate);
        } else if (employeeId != null) {
            return jdbc.queryForList(
                "SELECT s.*, m.first_name, m.last_name, m.position, m.department " +
                "FROM staff_shifts s JOIN staff_members m ON s.staff_id = m.id " +
                "WHERE s.staff_id = ? ORDER BY s.shift_date", employeeId);
        } else if (date != null) {
            return jdbc.queryForList(
                "SELECT s.*, m.first_name, m.last_name, m.position, m.department, m.location as employee_location " +
                "FROM staff_shifts s JOIN staff_members m ON s.staff_id = m.id " +
                "WHERE s.shift_date = ? ORDER BY m.last_name, m.first_name", date);
        } else if (startDate != null && endDate != null) {
            return jdbc.queryForList(
                "SELECT s.*, m.first_name, m.last_name, m.position, m.department, m.location as employee_location " +
                "FROM staff_shifts s JOIN staff_members m ON s.staff_id = m.id " +
                "WHERE s.shift_date BETWEEN ? AND ? ORDER BY s.shift_date, m.last_name",
                startDate, endDate);
        }
        return List.of();
    }

    @PostMapping("/{staffId}")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> create(@PathVariable long staffId, @RequestBody CreateShiftsRequest req) {
        JdbcTemplate jdbc = jdbc();
        List<LocalDate> dates = resolveDates(req);
        String status = req.status() != null ? req.status() : "SCHEDULED";
        int location = req.location() != null ? req.location() : 0;
        List<Map<String, Object>> created = new ArrayList<>();
        for (LocalDate d : dates) {
            try {
                jdbc.update(
                    "INSERT IGNORE INTO staff_shifts (staff_id, shift_date, status, location, notes) VALUES (?, ?, ?, ?, ?)",
                    staffId, java.sql.Date.valueOf(d), status, location, req.notes()
                );
                created.add(Map.of("staffId", staffId, "date", d.toString(), "status", status));
            } catch (Exception ignored) {}
        }
        return created;
    }

    @DeleteMapping("/{staffId}/{shiftDate}")
    public Map<String, Object> delete(@PathVariable long staffId, @PathVariable String shiftDate) {
        int rows = jdbc().update(
            "DELETE FROM staff_shifts WHERE staff_id = ? AND shift_date = ?",
            staffId, java.sql.Date.valueOf(shiftDate)
        );
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("staffId", staffId, "date", shiftDate);
    }

    private List<LocalDate> resolveDates(CreateShiftsRequest req) {
        List<LocalDate> dates = new ArrayList<>();
        if (req.dates() != null && !req.dates().isEmpty()) {
            for (String d : req.dates()) dates.add(LocalDate.parse(d));
        } else if (req.startDate() != null && req.endDate() != null) {
            LocalDate cur = LocalDate.parse(req.startDate());
            LocalDate end = LocalDate.parse(req.endDate());
            while (!cur.isAfter(end)) {
                if (req.daysOfWeek() == null || req.daysOfWeek().isEmpty() ||
                    req.daysOfWeek().contains(cur.getDayOfWeek().getValue())) {
                    dates.add(cur);
                }
                cur = cur.plusDays(1);
            }
        }
        return dates;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
