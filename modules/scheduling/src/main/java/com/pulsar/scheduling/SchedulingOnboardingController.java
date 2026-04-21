package com.pulsar.scheduling;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.sql.Time;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/scheduling/onboarding")
@RequireModule("scheduling")
public class SchedulingOnboardingController {
    private final TenantDataSources tenantDs;

    public SchedulingOnboardingController(TenantDataSources tenantDs) {
        this.tenantDs = tenantDs;
    }

    public record OnboardingRequest(
        @NotBlank String timezone,
        @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$") String businessHoursStart,
        @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$") String businessHoursEnd
    ) {}

    @GetMapping
    public Map<String, Object> status() {
        JdbcTemplate jdbc = jdbc();
        return jdbc.queryForList("SELECT timezone, business_hours_start, business_hours_end, onboarded_at FROM scheduling_settings WHERE id = 1")
            .stream().findFirst()
            .map(row -> Map.of(
                "onboarded", (Object) true,
                "timezone", row.get("timezone"),
                "businessHoursStart", row.get("business_hours_start").toString(),
                "businessHoursEnd", row.get("business_hours_end").toString()
            ))
            .orElse(Map.of("onboarded", false));
    }

    @PostMapping
    public Map<String, Object> complete(@Valid @RequestBody OnboardingRequest req) {
        Time start = Time.valueOf(LocalTime.parse(req.businessHoursStart() + ":00"));
        Time end = Time.valueOf(LocalTime.parse(req.businessHoursEnd() + ":00"));
        if (!end.after(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business_hours_end_must_be_after_start");
        }
        JdbcTemplate jdbc = jdbc();
        jdbc.update(
            "INSERT INTO scheduling_settings (id, timezone, business_hours_start, business_hours_end) " +
            "VALUES (1, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE timezone = VALUES(timezone), " +
            "business_hours_start = VALUES(business_hours_start), " +
            "business_hours_end = VALUES(business_hours_end)",
            req.timezone(), start, end
        );
        return Map.of("onboarded", true);
    }

    private JdbcTemplate jdbc() {
        var t = TenantContext.require();
        return new JdbcTemplate(tenantDs.forDb(t.dbName()));
    }
}
