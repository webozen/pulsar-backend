package com.pulsar.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.sql.Time;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SchedulingOnboardingControllerTest {

    private TenantDataSources tenantDs;
    private SchedulingOnboardingController controller;
    private Validator validator;

    @BeforeEach
    void setUp() {
        tenantDs = mock(TenantDataSources.class);
        when(tenantDs.forDb(anyString())).thenReturn(mock(DataSource.class));
        controller = new SchedulingOnboardingController(tenantDs);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("scheduling"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- POST / complete() ------------------------------------------------

    @Test
    void complete_happyPath_writesSettingsAndReturnsOnboarded() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new SchedulingOnboardingController.OnboardingRequest(
                "America/Los_Angeles", "09:00", "17:00");
            Map<String, Object> out = controller.complete(req);

            assertEquals(true, out.get("onboarded"));
            verify(m.constructed().get(0)).update(
                anyString(),
                eq("America/Los_Angeles"),
                eq(Time.valueOf("09:00:00")),
                eq(Time.valueOf("17:00:00")));
        }
    }

    @Test
    void complete_endEqualToStart_throws400() {
        // Validation happens before jdbc() is called, so no JdbcTemplate should be
        // constructed — we just assert the 400 + that mockConstruction saw nothing.
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new SchedulingOnboardingController.OnboardingRequest(
                "UTC", "09:00", "09:00");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.complete(req));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertEquals(0, m.constructed().size(), "jdbc must not be touched on validation failure");
        }
    }

    @Test
    void complete_endBeforeStart_throws400() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new SchedulingOnboardingController.OnboardingRequest(
                "UTC", "17:00", "09:00");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.complete(req));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
    }

    // ---- jakarta @Pattern validation --------------------------------------
    // The controller relies on @Valid to enforce the HH:mm regex. We exercise
    // the validator directly since we're not running MockMvc.

    @Test
    void validator_rejectsSingleDigitHour() {
        var req = new SchedulingOnboardingController.OnboardingRequest(
            "UTC", "9:00", "17:00");
        var violations = validator.validate(req);
        assertEquals(1, violations.size());
    }

    @Test
    void validator_rejectsBlankTimezone() {
        var req = new SchedulingOnboardingController.OnboardingRequest(
            "", "09:00", "17:00");
        var violations = validator.validate(req);
        assertEquals(1, violations.size());
    }

    @Test
    void validator_rejectsBadRegexFormat() {
        var req = new SchedulingOnboardingController.OnboardingRequest(
            "UTC", "9am", "5pm");
        var violations = validator.validate(req);
        // Two fields fail regex
        assertEquals(2, violations.size());
    }

    @Test
    void validator_acceptsWellFormedRequest() {
        var req = new SchedulingOnboardingController.OnboardingRequest(
            "UTC", "09:00", "17:00");
        assertEquals(0, validator.validate(req).size());
    }

    // Sanity: a ConstraintViolationException would be how Spring surfaces this at
    // runtime. Not asserted directly (Spring's validation pipeline isn't wired here).
    @SuppressWarnings("unused")
    private void _refCompileOnly() { throw new ConstraintViolationException(Set.of()); }

    // ---- GET / status() ---------------------------------------------------

    @Test
    void status_noRow_returnsOnboardedFalse() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString())).thenReturn(List.of()))) {
            Map<String, Object> out = controller.status();
            assertEquals(false, out.get("onboarded"));
            assertEquals(1, out.size(), "no other keys when not onboarded");
        }
    }

    @Test
    void status_existingRow_returnsValues() {
        Map<String, Object> row = new HashMap<>();
        row.put("timezone", "UTC");
        row.put("business_hours_start", Time.valueOf("09:00:00"));
        row.put("business_hours_end", Time.valueOf("17:00:00"));
        row.put("onboarded_at", null);
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString())).thenReturn(List.of(row)))) {
            Map<String, Object> out = controller.status();
            assertEquals(true, out.get("onboarded"));
            assertEquals("UTC", out.get("timezone"));
            assertEquals("09:00:00", out.get("businessHoursStart"));
            assertEquals("17:00:00", out.get("businessHoursEnd"));
        }
    }
}
