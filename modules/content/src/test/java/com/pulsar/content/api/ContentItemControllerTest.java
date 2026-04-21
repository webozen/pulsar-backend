package com.pulsar.content.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.content.service.AnythingLlmClient;
import com.pulsar.content.service.ContentItemService;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct-instantiation tests for ContentItemController. We bypass the @RequireModule filter
 * and MockMvc wiring entirely and just call controller methods — the controller delegates
 * to the service and AnythingLlmClient, both mocked.
 */
class ContentItemControllerTest {

    private TenantDataSources tenantDs;
    private ContentItemService svc;
    private AnythingLlmClient llm;
    private ContentItemController controller;

    @BeforeEach
    void setUp() {
        tenantDs = Mockito.mock(TenantDataSources.class);
        svc = Mockito.mock(ContentItemService.class);
        llm = Mockito.mock(AnythingLlmClient.class);
        controller = new ContentItemController(tenantDs, svc, llm);

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

    // ---- create ----------------------------------------------------------

    @Test
    void create_happyPath_invokesServiceAndPushesToLlm() throws Exception {
        when(svc.create(any(), eq("Hello"), eq("general"), eq("runbook"),
            any())).thenReturn("hello");

        Map<String, Object> resp = controller.create(
            new ContentItemController.ItemRequest(
                "Hello", null, null, Map.of("content", "body")));

        assertEquals("hello", resp.get("itemId"));
        // Default category/type applied since request had nulls.
        verify(svc).create(any(), eq("Hello"), eq("general"), eq("runbook"), any());
        verify(llm).pushTextDocument(eq("acme"), eq("Hello"), anyString());
    }

    @Test
    void create_nullContentData_defaultsToEmptyMap() throws Exception {
        when(svc.create(any(), anyString(), anyString(), anyString(), any())).thenReturn("x");

        controller.create(new ContentItemController.ItemRequest("X", "c", "t", null));

        // No indexable body → skip the AnythingLLM push (prevents empty-doc pollution).
        verify(llm, never()).pushTextDocument(anyString(), anyString(), anyString());
    }

    @Test
    void create_emptyContentField_skipsLlmPush() throws Exception {
        when(svc.create(any(), anyString(), anyString(), anyString(), any())).thenReturn("x");

        controller.create(new ContentItemController.ItemRequest(
            "X", "c", "runbook", Map.of("content", "")));

        verify(llm, never()).pushTextDocument(anyString(), anyString(), anyString());
    }

    @Test
    void create_contactType_indexesRunbookField() throws Exception {
        when(svc.create(any(), anyString(), anyString(), anyString(), any())).thenReturn("x");

        controller.create(new ContentItemController.ItemRequest(
            "Stripe Support", "billing", "contact", Map.of("runbook", "Call 1-800-STRIPE")));

        verify(llm).pushTextDocument(eq("acme"), eq("Stripe Support"),
            eq("Stripe Support\n\nCall 1-800-STRIPE"));
    }

    @Test
    void create_trainingType_indexesDescriptionField() throws Exception {
        when(svc.create(any(), anyString(), anyString(), anyString(), any())).thenReturn("x");

        controller.create(new ContentItemController.ItemRequest(
            "Onboarding 101", "general", "training", Map.of("description", "Welcome materials")));

        verify(llm).pushTextDocument(eq("acme"), eq("Onboarding 101"),
            eq("Onboarding 101\n\nWelcome materials"));
    }

    // ---- update ----------------------------------------------------------

    @Test
    void update_notFound_throws404() throws Exception {
        when(svc.update(any(), eq("missing"), anyString(), anyString(), anyString(), any()))
            .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.update("missing",
                new ContentItemController.ItemRequest("T", "c", "t", Map.of())));
        assertEquals(404, ex.getStatusCode().value());
        verify(llm, never()).pushTextDocument(anyString(), anyString(), anyString());
    }

    @Test
    void update_success_returnsItemId() throws Exception {
        when(svc.update(any(), eq("hello"), eq("T"), eq("c"), eq("t"), any())).thenReturn(true);

        Map<String, Object> resp = controller.update("hello",
            new ContentItemController.ItemRequest("T", "c", "t", Map.of("content", "body")));

        assertEquals("hello", resp.get("itemId"));
        verify(llm).pushTextDocument(eq("acme"), eq("T"), anyString());
    }

    @Test
    void update_metadataOnly_skipsLlmPush() throws Exception {
        when(svc.update(any(), eq("hello"), eq("New Title"), anyString(), anyString(), any()))
            .thenReturn(true);

        controller.update("hello", new ContentItemController.ItemRequest(
            "New Title", "c", "runbook", Map.of()));

        // Title-only edit: DB updates but we don't re-index an empty doc.
        verify(llm, never()).pushTextDocument(anyString(), anyString(), anyString());
    }

    // ---- delete ----------------------------------------------------------

    @Test
    void delete_notFound_throws404() {
        when(svc.delete(any(), eq("missing"))).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.delete("missing"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void delete_success_returnsDeletedId() {
        when(svc.delete(any(), eq("hello"))).thenReturn(true);
        Map<String, Object> resp = controller.delete("hello");
        assertEquals("hello", resp.get("deleted"));
    }

    // ---- sanity: service exists check on service wiring ------------------

    @Test
    void list_noFilters_callsFindAllWithNulls() {
        when(svc.findAll(any(), eq(null), eq(null))).thenReturn(List.of());
        controller.list(null, null);
        verify(svc).findAll(any(), eq(null), eq(null));
    }

    // ---- findByItemId sanity via service contract (optional safety) -----

    @Test
    void findByItemIdContract_isCalledFromUpdateOnlyWhenCollisionLogicRequires() {
        // Covered in service tests, but this asserts the controller doesn't call findByItemId directly.
        when(svc.findByItemId(any(), anyString())).thenReturn(Optional.empty());
        when(svc.delete(any(), anyString())).thenReturn(true);
        controller.delete("any");
        verify(svc, never()).findByItemId(any(), anyString());
    }
}
