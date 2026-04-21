package com.pulsar.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import java.sql.Date;
import java.sql.Time;
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
import org.springframework.jdbc.core.JdbcTemplate;

class AttendanceControllerTest {

    private TenantDataSources tenantDs;
    private AttendanceController controller;

    @BeforeEach
    void setUp() {
        tenantDs = mock(TenantDataSources.class);
        when(tenantDs.forDb(anyString())).thenReturn(mock(DataSource.class));
        controller = new AttendanceController(tenantDs);
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("scheduling"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- GET /{staffId} ---------------------------------------------------

    @Test
    void getForEmployee_runsDescOrderedQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1), Map.of("id", 2))))) {
            List<Map<String, Object>> out = controller.getForEmployee(7L);
            assertEquals(2, out.size());
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture(), eq(7L));
            assertTrue(sql.getValue().contains("ORDER BY attendance_date DESC"),
                "must order records newest-first");
            assertTrue(sql.getValue().contains("WHERE staff_id = ?"));
        }
    }

    // ---- POST /bulk happy path --------------------------------------------

    @Test
    void bulkSave_allValid_returnsSuccessCounts() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1))) {
            var records = List.of(
                new AttendanceController.AttendanceRecord(1L, "2026-04-21", "09:00", "17:00", "normal"),
                new AttendanceController.AttendanceRecord(2L, "2026-04-21", "10:00", "18:30", null)
            );
            Map<String, Object> out = controller.bulkSave(new AttendanceController.BulkRequest(records));
            assertEquals(2, out.get("successCount"));
            assertEquals(0, out.get("errorCount"));
            assertEquals(true, out.get("success"));
            verify(m.constructed().get(0), times(2)).update(
                anyString(), any(Long.class), any(Date.class),
                any(Time.class), any(Time.class), any());
        }
    }

    @Test
    void bulkSave_nullClockInAndOut_passesNullsThrough() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1))) {
            var records = List.of(
                new AttendanceController.AttendanceRecord(1L, "2026-04-21", null, null, null)
            );
            Map<String, Object> out = controller.bulkSave(new AttendanceController.BulkRequest(records));
            assertEquals(1, out.get("successCount"));
            verify(m.constructed().get(0)).update(
                anyString(), eq(1L), eq(Date.valueOf("2026-04-21")),
                eq(null), eq(null), eq(null));
        }
    }

    // ---- POST /bulk silent-error path -------------------------------------

    @Test
    void bulkSave_malformedTime_incrementsErrorCountNotCrash() {
        // "bad" will fail Time.valueOf when controller appends ":00" (→ "bad:00"),
        // throwing IllegalArgumentException inside the loop.  Controller must swallow
        // it, count it as error, and continue processing subsequent records.
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1))) {
            var records = List.of(
                new AttendanceController.AttendanceRecord(1L, "2026-04-21", "09:00", "17:00", null),
                new AttendanceController.AttendanceRecord(2L, "2026-04-21", "bad", null, null),
                new AttendanceController.AttendanceRecord(3L, "2026-04-21", "10:00", "18:00", null)
            );
            Map<String, Object> out = controller.bulkSave(new AttendanceController.BulkRequest(records));
            assertEquals(2, out.get("successCount"));
            assertEquals(1, out.get("errorCount"));
        }
    }

    @Test
    void bulkSave_malformedDate_incrementsErrorCount() {
        // Bad attendanceDate (e.g. "not-a-date") throws in Date.valueOf.
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1))) {
            var records = List.of(
                new AttendanceController.AttendanceRecord(1L, "not-a-date", "09:00", "17:00", null)
            );
            Map<String, Object> out = controller.bulkSave(new AttendanceController.BulkRequest(records));
            assertEquals(0, out.get("successCount"));
            assertEquals(1, out.get("errorCount"));
        }
    }

    @Test
    void bulkSave_jdbcException_propagates() {
        // Infra failures (DB down, connection error) must NOT be silently counted as errors —
        // the caller needs to distinguish "1 bad record" from "whole batch failed".
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down")))) {
            var records = List.of(
                new AttendanceController.AttendanceRecord(1L, "2026-04-21", "09:00", "17:00", null)
            );
            org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> controller.bulkSave(new AttendanceController.BulkRequest(records)));
        }
    }

    @Test
    void bulkSave_singleDigitMinute_rejectedAsError() {
        // "9:5" fails the strict HH:mm regex. Treat as per-record validation error; continue batch.
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1))) {
            var records = List.of(
                new AttendanceController.AttendanceRecord(1L, "2026-04-21", "9:5", null, null),
                new AttendanceController.AttendanceRecord(2L, "2026-04-21", "09:00", "17:00", null)
            );
            Map<String, Object> out = controller.bulkSave(new AttendanceController.BulkRequest(records));
            assertEquals(1, out.get("successCount"), "valid record still saved");
            assertEquals(1, out.get("errorCount"), "malformed time rejected");
        }
    }

    @Test
    void bulkSave_emptyList_returnsZeroZero() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class)) {
            Map<String, Object> out = controller.bulkSave(
                new AttendanceController.BulkRequest(List.of()));
            assertEquals(0, out.get("successCount"));
            assertEquals(0, out.get("errorCount"));
        }
    }
}
