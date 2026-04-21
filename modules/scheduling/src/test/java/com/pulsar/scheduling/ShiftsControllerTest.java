package com.pulsar.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct-instantiation tests for ShiftsController. The controller builds a new JdbcTemplate
 * per call via `new JdbcTemplate(tenantDs.forDb(...))`, so we mock JdbcTemplate construction
 * via Mockito's `mockConstruction` hook.
 */
class ShiftsControllerTest {

    private TenantDataSources tenantDs;
    private ShiftsController controller;

    @BeforeEach
    void setUp() {
        tenantDs = mock(TenantDataSources.class);
        when(tenantDs.forDb(anyString())).thenReturn(mock(DataSource.class));
        controller = new ShiftsController(tenantDs);
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("scheduling"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- list() -----------------------------------------------------------

    @Test
    void list_noFilters_returnsEmptyListWithoutQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            List<Map<String, Object>> out = controller.list(null, null, null, null);
            assertEquals(List.of(), out);
            // Controller short-circuits to List.of() without hitting jdbc
            assertEquals(1, m.constructed().size());
            verify(m.constructed().get(0), never()).queryForList(anyString());
        }
    }

    @Test
    void list_dateFilter_runsSingleDateQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1))))) {
            List<Map<String, Object>> out = controller.list("2026-04-21", null, null, null);
            assertEquals(1, out.size());
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture(), eq("2026-04-21"));
            assertTrue(sql.getValue().contains("WHERE s.shift_date = ?"));
        }
    }

    @Test
    void list_rangeFilter_runsBetweenQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list(null, "2026-04-01", "2026-04-30", null);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture(), eq("2026-04-01"), eq("2026-04-30"));
            assertTrue(sql.getValue().contains("BETWEEN ? AND ?"));
        }
    }

    @Test
    void list_employeeIdOnly_runsEmployeeQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list(null, null, null, 42L);
            verify(m.constructed().get(0)).queryForList(anyString(), eq(42L));
        }
    }

    @Test
    void list_employeeWithRange_runsCombinedQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list(null, "2026-04-01", "2026-04-30", 42L);
            verify(m.constructed().get(0))
                .queryForList(anyString(), eq(42L), eq("2026-04-01"), eq("2026-04-30"));
        }
    }

    // ---- create() / resolveDates() ----------------------------------------

    @Test
    void create_explicitDatesList_passedThroughAsIs() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                List.of("2026-04-21", "2026-04-22", "2026-04-23"),
                null, null, null, null, null, "note");
            List<Map<String, Object>> out = controller.create(10L, req);

            assertEquals(3, out.size());
            JdbcTemplate jdbc = m.constructed().get(0);
            verify(jdbc, times(3)).update(
                eq("INSERT IGNORE INTO staff_shifts (staff_id, shift_date, status, location, notes) VALUES (?, ?, ?, ?, ?)"),
                eq(10L), any(Date.class), eq("SCHEDULED"), eq(0), eq("note"));
        }
    }

    @Test
    void create_dateRangeNoDaysOfWeek_insertsEveryDay() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, "2026-04-20", "2026-04-24", null, "CONFIRMED", 2, null);
            List<Map<String, Object>> out = controller.create(10L, req);

            assertEquals(5, out.size()); // Mon..Fri
            assertEquals("CONFIRMED", out.get(0).get("status"));
            verify(m.constructed().get(0), times(5))
                .update(anyString(), eq(10L), any(Date.class), eq("CONFIRMED"), eq(2), eq(null));
        }
    }

    @Test
    void create_dateRangeWithMonWedFri_filtersByIsoWeekday() {
        // 2026-04-20 is a Monday. Days Mon=1, Wed=3, Fri=5 → from Mon 20 to Sun 26:
        //   Mon 20, Wed 22, Fri 24 → 3 inserts.
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, "2026-04-20", "2026-04-26", List.of(1, 3, 5), null, null, null);
            List<Map<String, Object>> out = controller.create(10L, req);
            assertEquals(3, out.size());

            ArgumentCaptor<Date> dateCap = ArgumentCaptor.forClass(Date.class);
            verify(m.constructed().get(0), times(3))
                .update(anyString(), eq(10L), dateCap.capture(), anyString(), eq(0), any());
            List<Date> stored = dateCap.getAllValues();
            assertEquals(Date.valueOf("2026-04-20"), stored.get(0));
            assertEquals(Date.valueOf("2026-04-22"), stored.get(1));
            assertEquals(Date.valueOf("2026-04-24"), stored.get(2));
        }
    }

    @Test
    void create_dateRangeWithSundayOnly_matchesSunday() {
        // Confirm ISO weekday mapping: Sun=7 (LocalDate.getDayOfWeek().getValue()).
        // 2026-04-19 is Sunday.
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, "2026-04-19", "2026-04-25", List.of(7), null, null, null);
            List<Map<String, Object>> out = controller.create(10L, req);
            assertEquals(1, out.size());
            assertEquals("2026-04-19", out.get(0).get("date"));
        }
    }

    @Test
    void create_startEqualsEnd_insertsOneDay() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, "2026-04-21", "2026-04-21", null, null, null, null);
            List<Map<String, Object>> out = controller.create(10L, req);
            assertEquals(1, out.size());
        }
    }

    @Test
    void create_startAfterEnd_emptyResult() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, "2026-04-25", "2026-04-21", null, null, null, null);
            List<Map<String, Object>> out = controller.create(10L, req);
            assertEquals(List.of(), out);
            verify(m.constructed().get(0), never())
                .update(anyString(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void create_emptyDaysOfWeek_treatedAsNoFilter() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, "2026-04-20", "2026-04-22", List.of(), null, null, null);
            List<Map<String, Object>> out = controller.create(10L, req);
            assertEquals(3, out.size());
        }
    }

    @Test
    void create_nullDatesAndNullRange_emptyResult() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                null, null, null, null, null, null, null);
            List<Map<String, Object>> out = controller.create(10L, req);
            assertEquals(List.of(), out);
        }
    }

    @Test
    void create_returnedPayloadShape_hasStaffIdDateStatus() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            var req = new ShiftsController.CreateShiftsRequest(
                List.of("2026-04-21"), null, null, null, null, null, null);
            List<Map<String, Object>> out = controller.create(7L, req);
            Map<String, Object> row = out.get(0);
            assertEquals(7L, row.get("staffId"));
            assertEquals("2026-04-21", row.get("date"));
            assertEquals("SCHEDULED", row.get("status"));
        }
    }

    // ---- delete() ---------------------------------------------------------

    @Test
    void delete_success() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(1))) {
            Map<String, Object> out = controller.delete(5L, "2026-04-21");
            assertEquals(5L, out.get("staffId"));
            assertEquals("2026-04-21", out.get("date"));
        }
    }

    @Test
    void delete_notFound_throws404() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(0))) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete(5L, "2026-04-21"));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }
}
