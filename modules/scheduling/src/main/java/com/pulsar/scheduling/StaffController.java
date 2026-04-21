package com.pulsar.scheduling;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/office/staff")
@RequireModule("scheduling")
public class StaffController {

    private static final List<String> VALID_STATUSES =
        List.of("ACTIVE", "INACTIVE", "ON_LEAVE", "TERMINATED");

    private final TenantDataSources tenantDs;

    public StaffController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record StaffRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String email,
        String phone,
        String position,
        String department,
        String status,
        String hireDate,
        Integer location,
        String address,
        String emergencyContact
    ) {}

    @GetMapping
    public List<Map<String, Object>> list(
        @RequestParam(name = "department", required = false) String department,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "activeOnly", required = false) Boolean activeOnly
    ) {
        // Compose filters instead of short-circuiting. Previously activeOnly=true ignored
        // department/status, silently dropping filters callers thought they had applied.
        StringBuilder sql = new StringBuilder("SELECT * FROM staff_members");
        List<Object> args = new ArrayList<>();
        List<String> wheres = new ArrayList<>();
        if (Boolean.TRUE.equals(activeOnly)) wheres.add("status = 'ACTIVE'");
        if (department != null) { wheres.add("department = ?"); args.add(department); }
        if (status != null) { wheres.add("status = ?"); args.add(status); }
        if (!wheres.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", wheres));
        sql.append(" ORDER BY last_name, first_name");

        JdbcTemplate jdbc = jdbc();
        return args.isEmpty()
            ? jdbc.queryForList(sql.toString())
            : jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody StaffRequest req) {
        String status = req.status() != null && VALID_STATUSES.contains(req.status()) ? req.status() : "ACTIVE";
        int location = req.location() != null ? req.location() : 0;
        JdbcTemplate jdbc = jdbc();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO staff_members (first_name, last_name, email, phone, position, department, status, hire_date, location, address, emergency_contact) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, req.firstName());
            ps.setString(2, req.lastName());
            ps.setString(3, req.email());
            ps.setString(4, req.phone());
            ps.setString(5, req.position());
            ps.setString(6, req.department());
            ps.setString(7, status);
            ps.setObject(8, req.hireDate() != null ? java.sql.Date.valueOf(req.hireDate()) : null);
            ps.setInt(9, location);
            ps.setString(10, req.address());
            ps.setString(11, req.emergencyContact());
            return ps;
        }, keys);
        return Map.of("id", keys.getKey().longValue());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody StaffRequest req) {
        String status = req.status() != null && VALID_STATUSES.contains(req.status()) ? req.status() : "ACTIVE";
        int location = req.location() != null ? req.location() : 0;
        int rows = jdbc().update(
            "UPDATE staff_members SET first_name=?, last_name=?, email=?, phone=?, position=?, department=?, status=?, hire_date=?, location=?, address=?, emergency_contact=? WHERE id=?",
            req.firstName(), req.lastName(), req.email(), req.phone(),
            req.position(), req.department(), status,
            req.hireDate() != null ? java.sql.Date.valueOf(req.hireDate()) : null,
            location, req.address(), req.emergencyContact(), id
        );
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("id", id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        int rows = jdbc().update("UPDATE staff_members SET status = 'TERMINATED' WHERE id = ?", id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("id", id);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
