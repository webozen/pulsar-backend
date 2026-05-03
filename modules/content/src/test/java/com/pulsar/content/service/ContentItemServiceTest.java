package com.pulsar.content.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for ContentItemService — no Spring, JdbcTemplate +
 * AnythingLlmClient mocked. The default fixture has AnythingLLM
 * "not configured" so the existing JDBC-focused tests don't try to
 * hit the workspace; the AnythingLLM-sync tests at the bottom
 * explicitly enable it.
 */
class ContentItemServiceTest {

    private static final String TENANT = "acme";

    private JdbcTemplate jdbc;
    private AnythingLlmClient llm;
    private ContentItemService svc;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        llm = Mockito.mock(AnythingLlmClient.class);
        when(llm.isConfigured()).thenReturn(false);
        svc = new ContentItemService(llm);
    }

    // ---- findAll ---------------------------------------------------------

    @Test
    void findAll_noFilters_usesPlainQuery() {
        when(jdbc.queryForList("SELECT * FROM content_items ORDER BY created_at DESC"))
            .thenReturn(List.of(Map.of("item_id", "a")));

        List<Map<String, Object>> out = svc.findAll(jdbc, null, null);

        assertEquals(1, out.size());
        verify(jdbc).queryForList("SELECT * FROM content_items ORDER BY created_at DESC");
    }

    @Test
    void findAll_categoryOnly_appliesCategoryFilter() {
        when(jdbc.queryForList(anyString(), eq("guides"))).thenReturn(List.of());

        svc.findAll(jdbc, "guides", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq("guides"));
        assertTrue(sql.getValue().contains("category = ?"));
        assertFalse(sql.getValue().contains("type = ?"));
    }

    @Test
    void findByItemId_returnsRowWhenPresent() {
        when(jdbc.queryForList(anyString(), eq("hello")))
            .thenReturn(List.of(Map.of("item_id", "hello")));

        Optional<Map<String, Object>> out = svc.findByItemId(jdbc, "hello");

        assertTrue(out.isPresent());
        assertEquals("hello", out.get().get("item_id"));
    }

    // ---- create (slug generation + collision) ----------------------------

    @Test
    void create_slugifiesTitle() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        String id = svc.create(jdbc, TENANT, "Hello World!", "general", "runbook", Map.of("content", "x"));
        assertEquals("hello-world", id);
    }

    @Test
    void create_stripsLeadingAndTrailingPunctuation() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        String id = svc.create(jdbc, TENANT, "  --- Awesome Thing!!!  ", "g", "t", Map.of());
        assertEquals("awesome-thing", id);
    }

    @Test
    void create_onCollision_appendsIncrementingSuffix() throws Exception {
        Map<String, Object> taken = Map.of("item_id", "hello");
        when(jdbc.queryForList(anyString(), eq("hello"))).thenReturn(List.of(taken));
        when(jdbc.queryForList(anyString(), eq("hello-2"))).thenReturn(List.of(taken));
        when(jdbc.queryForList(anyString(), eq("hello-3"))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        String id = svc.create(jdbc, TENANT, "hello", "g", "t", Map.of());

        assertEquals("hello-3", id);
    }

    @Test
    void create_insertsSerializedJsonContentData_andNullDoc_whenLlmDisabled() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> data = new HashMap<>();
        data.put("content", "hello");
        data.put("tags", List.of("a", "b"));
        svc.create(jdbc, TENANT, "Title", "general", "runbook", data);

        ArgumentCaptor<Object> a1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a4 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a5 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a6 = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(
            anyString(),
            a1.capture(), a2.capture(), a3.capture(), a4.capture(), a5.capture(), a6.capture()
        );
        // Position 5 = content_data JSON; position 6 = anythingllm_doc.
        Object contentDataArg = a5.getValue();
        assertTrue(contentDataArg instanceof String,
            "content_data must be serialized to JSON string, got " + contentDataArg.getClass());
        String json = (String) contentDataArg;
        assertTrue(json.contains("\"content\":\"hello\""));
        assertTrue(json.contains("\"tags\""));
        // LLM disabled in setUp() — anythingllm_doc must be NULL.
        assertNull(a6.getValue());
        verify(llm, never()).pushTextDocument(anyString(), anyString(), anyString());
    }

    // ---- update ----------------------------------------------------------

    @Test
    void update_returnsTrueWhenRowUpdated() throws Exception {
        when(jdbc.queryForList(anyString(), eq("slug")))
            .thenReturn(List.of(Map.of("item_id", "slug")));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(svc.update(jdbc, TENANT, "slug", "T", "c", "t", Map.of()));
    }

    @Test
    void update_returnsFalseWhenItemNotFound() throws Exception {
        when(jdbc.queryForList(anyString(), eq("slug"))).thenReturn(List.of());
        assertFalse(svc.update(jdbc, TENANT, "slug", "T", "c", "t", Map.of()));
        // No UPDATE should have fired.
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    // ---- delete ----------------------------------------------------------

    @Test
    void delete_returnsTrueWhenRowDeleted() {
        when(jdbc.queryForList(anyString(), eq("slug")))
            .thenReturn(List.of(Map.of("item_id", "slug")));
        when(jdbc.update(anyString(), eq("slug"))).thenReturn(1);
        assertTrue(svc.delete(jdbc, TENANT, "slug"));
    }

    @Test
    void delete_returnsFalseWhenItemNotFound() {
        when(jdbc.queryForList(anyString(), eq("missing"))).thenReturn(List.of());
        assertFalse(svc.delete(jdbc, TENANT, "missing"));
        // Should never reach DELETE if the row doesn't exist.
        verify(jdbc, never()).update(anyString(), eq("missing"));
    }

    @Test
    void delete_removesAnythingLlmDocBeforeDeletingRow() {
        when(jdbc.queryForList(anyString(), eq("slug")))
            .thenReturn(List.of(Map.of("item_id", "slug", "anythingllm_doc", "custom-docs/abc")));
        when(jdbc.update(anyString(), eq("slug"))).thenReturn(1);

        svc.delete(jdbc, TENANT, "slug");

        verify(llm).removeDocument(TENANT, "custom-docs/abc");
    }

    // ---- toResponse ------------------------------------------------------

    @Test
    void toResponse_parsesJsonAndMergesColumns() {
        Map<String, Object> row = new HashMap<>();
        row.put("item_id", "hello");
        row.put("title", "Hello");
        row.put("category", "general");
        row.put("type", "runbook");
        row.put("content_data", "{\"content\":\"body\",\"tags\":[\"a\"]}");
        row.put("created_at", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        Map<String, Object> out = svc.toResponse(row);

        assertEquals("hello", out.get("itemId"));
        assertEquals("Hello", out.get("title"));
        assertEquals("general", out.get("category"));
        assertEquals("runbook", out.get("type"));
        assertEquals("body", out.get("content"));
        assertEquals(List.of("a"), out.get("tags"));
        assertTrue(out.get("createdAt") instanceof String);
    }

    @Test
    void toResponse_nullContentData_returnsErrorShape() {
        Map<String, Object> row = new HashMap<>();
        row.put("item_id", "hello");
        row.put("title", "Hello");
        row.put("content_data", null);
        row.put("created_at", Timestamp.from(Instant.now()));

        Map<String, Object> out = svc.toResponse(row);

        assertEquals("hello", out.get("itemId"));
        assertEquals("Invalid data", out.get("error"));
    }

    @Test
    void toResponse_invalidJson_returnsErrorShape() {
        Map<String, Object> row = new HashMap<>();
        row.put("item_id", "bad");
        row.put("title", "Bad");
        row.put("content_data", "not-json{");
        row.put("created_at", Timestamp.from(Instant.now()));

        Map<String, Object> out = svc.toResponse(row);

        assertEquals("bad", out.get("itemId"));
        assertEquals("Invalid data", out.get("error"));
    }

    @Test
    void create_noCollision_queriesItemIdExactlyOnce() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        svc.create(jdbc, TENANT, "Unique Title", "g", "t", Map.of());

        verify(jdbc, times(1)).queryForList(anyString(), anyString());
        verify(jdbc, never()).queryForList(anyString());
    }

    // ---- AnythingLLM sync ------------------------------------------------

    @Test
    void create_pushesToWorkspace_andStoresReturnedDocLocation() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.pushTextDocument(eq(TENANT), eq("Hello"), anyString()))
            .thenReturn("custom-docs/raw-text-hello.json");
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        svc.create(jdbc, TENANT, "Hello", "general", "runbook", Map.of("content", "Body text"));

        ArgumentCaptor<Object> a6 = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), a6.capture());
        assertEquals("custom-docs/raw-text-hello.json", a6.getValue());
    }

    @Test
    void create_skipsPush_whenBodyHasNoIndexableText() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        // contact with all blank fields — formatter produces only the
        // header (title + type line), which the service treats as "nothing
        // to embed" so no push happens.
        svc.create(jdbc, TENANT, "Empty Contact", "general", "contact", Map.of());

        verify(llm, never()).pushTextDocument(anyString(), anyString(), anyString());
    }

    @Test
    void update_replacesPreviousDoc_thenPushesNew() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.pushTextDocument(eq(TENANT), eq("Updated"), anyString()))
            .thenReturn("custom-docs/raw-text-updated.json");
        when(jdbc.queryForList(anyString(), eq("slug")))
            .thenReturn(List.of(Map.of("item_id", "slug", "anythingllm_doc", "custom-docs/old.json")));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        boolean ok = svc.update(jdbc, TENANT, "slug", "Updated", "general", "runbook",
            Map.of("content", "new body"));

        assertTrue(ok);
        // Old doc removed BEFORE new push, so retrieval can't return both.
        var inOrder = Mockito.inOrder(llm);
        inOrder.verify(llm).removeDocument(TENANT, "custom-docs/old.json");
        inOrder.verify(llm).pushTextDocument(eq(TENANT), eq("Updated"), anyString());
    }

    @Test
    void update_pushFailureDoesNotFailWrite() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.pushTextDocument(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("boom"));
        when(jdbc.queryForList(anyString(), eq("slug")))
            .thenReturn(List.of(Map.of("item_id", "slug")));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        // Should not throw; the row should still be persisted.
        boolean ok = svc.update(jdbc, TENANT, "slug", "T", "c", "t",
            Map.of("content", "body"));
        assertTrue(ok);
    }
}
