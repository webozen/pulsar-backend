package com.pulsar.content.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.content.service.ContentItemService;
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
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct-instantiation tests for ContentItemController. The controller is
 * now a thin pass-through to ContentItemService — including the AnythingLLM
 * sync, which lives entirely in the service. These tests only verify the
 * controller's contract: HTTP-level concerns (defaults, 404 mapping,
 * tenantSlug + itemId arg passing). The push/replace/remove behavior is
 * covered by ContentItemServiceTest, where it's actually implemented.
 */
class ContentItemControllerTest {

    private TenantDataSources tenantDs;
    private ContentItemService svc;
    private ContentItemController controller;

    @BeforeEach
    void setUp() {
        tenantDs = Mockito.mock(TenantDataSources.class);
        svc = Mockito.mock(ContentItemService.class);
        controller = new ContentItemController(tenantDs, svc);

        when(tenantDs.forDb(anyString())).thenReturn(Mockito.mock(DataSource.class));
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("content"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- list ------------------------------------------------------------

    @Test
    void list_forwardsFiltersAndMapsToResponses() {
        Map<String, Object> row = Map.of("item_id", "a");
        when(svc.findAll(any(), eq("guides"), eq("runbook"))).thenReturn(List.of(row));
        when(svc.toResponse(row)).thenReturn(Map.of("itemId", "a"));

        List<Map<String, Object>> out = controller.list("guides", "runbook");

        assertEquals(1, out.size());
        assertEquals("a", out.get(0).get("itemId"));
        verify(svc).findAll(any(), eq("guides"), eq("runbook"));
    }

    @Test
    void list_noFilters_callsFindAllWithNulls() {
        when(svc.findAll(any(), eq(null), eq(null))).thenReturn(List.of());
        controller.list(null, null);
        verify(svc).findAll(any(), eq(null), eq(null));
    }

    // ---- create ----------------------------------------------------------

    @Test
    void create_passesTenantSlugAndDefaults() throws Exception {
        when(svc.create(any(), eq("acme"), eq("Hello"), eq("general"), eq("runbook"), any()))
            .thenReturn("hello");

        Map<String, Object> resp = controller.create(
            new ContentItemController.ItemRequest(
                "Hello", null, null, Map.of("content", "body")));

        assertEquals("hello", resp.get("itemId"));
        // Default category/type applied when request had nulls.
        verify(svc).create(any(), eq("acme"), eq("Hello"), eq("general"), eq("runbook"), any());
    }

    @Test
    void create_nullContentData_defaultsToEmptyMap() throws Exception {
        when(svc.create(any(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn("x");

        controller.create(new ContentItemController.ItemRequest("X", "c", "t", null));

        // Service got an empty map, not null — verified by capturing the
        // argument would be more thorough, but a simple presence check is
        // enough for the contract here.
        verify(svc).create(any(), eq("acme"), eq("X"), eq("c"), eq("t"), eq(Map.of()));
    }

    // ---- update ----------------------------------------------------------

    @Test
    void update_notFound_throws404() throws Exception {
        when(svc.update(any(), eq("acme"), eq("missing"), anyString(), anyString(), anyString(), any()))
            .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.update("missing",
                new ContentItemController.ItemRequest("T", "c", "t", Map.of())));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void update_success_returnsItemId() throws Exception {
        when(svc.update(any(), eq("acme"), eq("hello"), eq("T"), eq("c"), eq("t"), any()))
            .thenReturn(true);

        Map<String, Object> resp = controller.update("hello",
            new ContentItemController.ItemRequest("T", "c", "t", Map.of("content", "body")));

        assertEquals("hello", resp.get("itemId"));
    }

    // ---- delete ----------------------------------------------------------

    @Test
    void delete_notFound_throws404() {
        when(svc.delete(any(), eq("acme"), eq("missing"))).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.delete("missing"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void delete_success_returnsDeletedId() {
        when(svc.delete(any(), eq("acme"), eq("hello"))).thenReturn(true);
        Map<String, Object> resp = controller.delete("hello");
        assertEquals("hello", resp.get("deleted"));
        verify(svc, never()).findByItemId(any(), anyString());
    }
}
