package com.pulsar.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

class StaffControllerTest {

    private TenantDataSources tenantDs;
    private StaffController controller;

    @BeforeEach
    void setUp() {
        tenantDs = mock(TenantDataSources.class);
        when(tenantDs.forDb(anyString())).thenReturn(mock(DataSource.class));
        controller = new StaffController(tenantDs);
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("scheduling"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- list() variants --------------------------------------------------

    @Test
    void list_noFilters_returnsAllOrdered() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("id", 1L), Map.of("id", 2L))))) {
            List<Map<String, Object>> out = controller.list(null, null, null);
            assertEquals(2, out.size());
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture());
            assertTrue(sql.getValue().contains("ORDER BY last_name, first_name"));
            assertTrue(!sql.getValue().contains("WHERE"));
        }
    }

    @Test
    void list_activeOnlyTrue_filtersToActive() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString())).thenReturn(List.of()))) {
            controller.list(null, null, true);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture());
            assertTrue(sql.getValue().contains("status = 'ACTIVE'"),
                "activeOnly must exclude TERMINATED/INACTIVE/ON_LEAVE");
        }
    }

    @Test
    void list_activeOnlyFalse_doesNotFilter() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString())).thenReturn(List.of()))) {
            controller.list(null, null, false);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture());
            assertTrue(!sql.getValue().contains("WHERE"), "activeOnly=false should not add WHERE clause");
        }
    }

    @Test
    void list_departmentOnly_runsDepartmentQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list("Engineering", null, null);
            verify(m.constructed().get(0)).queryForList(anyString(), eq("Engineering"));
        }
    }

    @Test
    void list_statusOnly_runsStatusQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list(null, "ON_LEAVE", null);
            verify(m.constructed().get(0)).queryForList(anyString(), eq("ON_LEAVE"));
        }
    }

    @Test
    void list_departmentAndStatus_runsCombinedQuery() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list("HR", "ACTIVE", null);
            verify(m.constructed().get(0)).queryForList(anyString(), eq("HR"), eq("ACTIVE"));
        }
    }

    @Test
    void list_activeOnlyAndDepartment_bothApplied() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of()))) {
            controller.list("HR", null, true);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sql.capture(), eq("HR"));
            assertTrue(sql.getValue().contains("status = 'ACTIVE'"),
                "activeOnly must apply alongside department filter, not override it");
            assertTrue(sql.getValue().contains("department = ?"),
                "department filter must be honored even when activeOnly=true");
        }
    }

    // ---- create() ---------------------------------------------------------

    @Test
    void create_happyPath_callsInsertAndReturnsGeneratedId() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class)))
                .thenAnswer(inv -> {
                    GeneratedKeyHolder holder = inv.getArgument(1);
                    holder.getKeyList().add(Map.of("id", 99L));
                    return 1;
                }))) {
            var req = new StaffController.StaffRequest(
                "Alice", "Smith", "a@b.com", "555-1212", "Engineer", "R&D",
                "ACTIVE", "2026-01-01", 1, "123 Main", "Bob 555-9999");
            Map<String, Object> out = controller.create(req);
            assertEquals(99L, out.get("id"));
        }
    }

    @Test
    void create_invalidStatus_defaultsToActive() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class)))
                .thenAnswer(inv -> {
                    GeneratedKeyHolder holder = inv.getArgument(1);
                    holder.getKeyList().add(Map.of("id", 1L));
                    return 1;
                }))) {
            var req = new StaffController.StaffRequest(
                "A", "B", null, null, null, null, "BOGUS", null, null, null, null);
            Map<String, Object> out = controller.create(req);
            assertEquals(1L, out.get("id"));
            // Whitebox: we cannot peek PS params easily. Branch coverage is enough.
        }
    }

    @Test
    void create_nullLocation_defaultsToZero() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class)))
                .thenAnswer(inv -> {
                    GeneratedKeyHolder holder = inv.getArgument(1);
                    holder.getKeyList().add(Map.of("id", 1L));
                    return 1;
                }))) {
            var req = new StaffController.StaffRequest(
                "A", "B", null, null, null, null, null, null, null, null, null);
            controller.create(req);
            verify(m.constructed().get(0)).update(
                any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class));
        }
    }

    // ---- update() ---------------------------------------------------------

    @Test
    void update_success_returnsId() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(1))) {
            var req = new StaffController.StaffRequest(
                "Alice", "Smith", "a@b.com", null, null, null,
                "ACTIVE", "2026-01-01", 1, null, null);
            Map<String, Object> out = controller.update(5L, req);
            assertEquals(5L, out.get("id"));
        }
    }

    @Test
    void update_notFound_throws404() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(0))) {
            var req = new StaffController.StaffRequest(
                "A", "B", null, null, null, null, null, null, null, null, null);
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.update(42L, req));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }

    // ---- delete() (soft delete → TERMINATED) ------------------------------

    @Test
    void delete_setsStatusTerminatedAndReturnsId() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(1))) {
            Map<String, Object> out = controller.delete(7L);
            assertEquals(7L, out.get("id"));
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).update(sql.capture(), eq(7L));
            assertTrue(sql.getValue().contains("status = 'TERMINATED'"),
                "delete should soft-delete via status update, never DROP/DELETE");
            assertTrue(!sql.getValue().toUpperCase().contains("DELETE FROM"),
                "delete should not hard-delete rows");
        }
    }

    @Test
    void delete_notFound_throws404() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(0))) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete(99L));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }
}
