package com.pulsar.callermatch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.callermatch.CallerMatchEventService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class CallerMatchControllerTest {

    private TenantDataSources tenantDs;
    private CallerMatchEventService eventService;
    private CallerMatchController controller;

    @BeforeEach
    void setUp() {
        tenantDs = mock(TenantDataSources.class);
        when(tenantDs.forDb(anyString())).thenReturn(mock(DataSource.class));
        eventService = mock(CallerMatchEventService.class);
        controller = new CallerMatchController(tenantDs, eventService);
        TenantContext.set(new TenantInfo(1L, "acme", "Acme", "acme_db", Set.of("caller-match"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // lookup() — delegates to eventService.buildCard
    // =========================================================================

    @Test
    void lookup_delegates_to_event_service_build_card() {
        Map<String, Object> expected = Map.of("phone", "+15551234567", "matched", true, "firstName", "Jane");
        when(eventService.buildCard(eq("+15551234567"), eq("acme_db"))).thenReturn(expected);

        var response = controller.lookup("+15551234567");

        assertNotNull(response.getBody());
        assertEquals(expected, response.getBody());
        verify(eventService).buildCard("+15551234567", "acme_db");
    }

    @Test
    void lookup_returns_unmatched_card_from_service() {
        Map<String, Object> card = Map.of("phone", "+15559990000", "matched", false, "error", "opendental_ai_not_onboarded");
        when(eventService.buildCard(anyString(), anyString())).thenReturn(card);

        var response = controller.lookup("+15559990000");

        assertNotNull(response.getBody());
        assertFalse(Boolean.TRUE.equals(response.getBody().get("matched")));
        assertEquals("opendental_ai_not_onboarded", response.getBody().get("error"));
    }

    // =========================================================================
    // recent()
    // =========================================================================

    @Test
    void recent_returns_query_result() {
        List<Map<String, Object>> expected = List.of(
            Map.of("id", 1L, "phone", "4015551234"),
            Map.of("id", 2L, "phone", "4015559999")
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(expected))) {

            List<Map<String, Object>> result = controller.recent();
            assertEquals(expected, result);
        }
    }

    @Test
    void recent_uses_caller_match_log_table() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of()))) {

            controller.recent();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(m.constructed().get(0)).queryForList(sqlCaptor.capture());
            assertTrue(sqlCaptor.getValue().contains("caller_match_log"),
                "recent() SQL must query the caller_match_log table");
        }
    }

    // =========================================================================
    // subscribe()
    // =========================================================================

    @Test
    void subscribe_delegates_to_event_service() {
        SseEmitter emitter = new SseEmitter();
        when(eventService.subscribe("acme_db")).thenReturn(emitter);

        SseEmitter result = controller.subscribe();

        assertEquals(emitter, result);
        verify(eventService).subscribe("acme_db");
    }
}
