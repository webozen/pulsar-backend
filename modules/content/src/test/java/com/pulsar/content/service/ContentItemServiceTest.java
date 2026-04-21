package com.pulsar.content.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Unit tests for ContentItemService — no Spring, JdbcTemplate mocked. */
class ContentItemServiceTest {

    private JdbcTemplate jdbc;
    private ContentItemService svc;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        svc = new ContentItemService();
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
    void findAll_typeOnly_appliesTypeFilter() {
        when(jdbc.queryForList(anyString(), eq("runbook"))).thenReturn(List.of());

        svc.findAll(jdbc, null, "runbook");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq("runbook"));
        assertTrue(sql.getValue().contains("type = ?"));
        assertFalse(sql.getValue().contains("category = ?"));
    }

    @Test
    void findAll_bothFilters_appliesBoth() {
        when(jdbc.queryForList(anyString(), eq("guides"), eq("runbook"))).thenReturn(List.of());

        svc.findAll(jdbc, "guides", "runbook");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq("guides"), eq("runbook"));
        assertTrue(sql.getValue().contains("category = ?"));
        assertTrue(sql.getValue().contains("type = ?"));
    }

    // ---- findByItemId ----------------------------------------------------

    @Test
    void findByItemId_emptyRows_returnsEmptyOptional() {
        when(jdbc.queryForList(anyString(), eq("missing"))).thenReturn(List.of());

        Optional<Map<String, Object>> out = svc.findByItemId(jdbc, "missing");

        assertTrue(out.isEmpty());
    }

    @Test
    void findByItemId_returnsFirstRow() {
        Map<String, Object> row = Map.of("item_id", "hello");
        when(jdbc.queryForList(anyString(), eq("hello"))).thenReturn(List.of(row));

        Optional<Map<String, Object>> out = svc.findByItemId(jdbc, "hello");

        assertTrue(out.isPresent());
        assertEquals("hello", out.get().get("item_id"));
    }

    // ---- create (slug generation + collision) ----------------------------

    @Test
    void create_slugifiesTitle() throws Exception {
        // No existing rows => first slug wins.
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        String id = svc.create(jdbc, "Hello World!", "general", "runbook", Map.of("content", "x"));
        assertEquals("hello-world", id);
    }

    @Test
    void create_stripsLeadingAndTrailingPunctuation() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        String id = svc.create(jdbc, "  --- Awesome Thing!!!  ", "g", "t", Map.of());
        assertEquals("awesome-thing", id);
    }

    @Test
    void create_onCollision_appendsIncrementingSuffix() throws Exception {
        // First two lookups return a row (slug + slug-2 taken), third is empty.
        Map<String, Object> taken = Map.of("item_id", "hello");
        when(jdbc.queryForList(anyString(), eq("hello"))).thenReturn(List.of(taken));
        when(jdbc.queryForList(anyString(), eq("hello-2"))).thenReturn(List.of(taken));
        when(jdbc.queryForList(anyString(), eq("hello-3"))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        String id = svc.create(jdbc, "hello", "g", "t", Map.of());

        assertEquals("hello-3", id);
    }

    @Test
    void create_insertsSerializedJsonContentData() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> data = new HashMap<>();
        data.put("content", "hello");
        data.put("tags", List.of("a", "b"));
        svc.create(jdbc, "Title", "general", "runbook", data);

        ArgumentCaptor<Object> a1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a4 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a5 = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(
            anyString(),
            a1.capture(), a2.capture(), a3.capture(), a4.capture(), a5.capture()
        );
        // The 5th argument (content_data) should be a JSON string, not a Map.
        Object contentDataArg = a5.getValue();
        assertTrue(contentDataArg instanceof String,
            "content_data must be serialized to JSON string, got " + contentDataArg.getClass());
        String json = (String) contentDataArg;
        assertTrue(json.contains("\"content\":\"hello\""));
        assertTrue(json.contains("\"tags\""));
    }

    // ---- update ----------------------------------------------------------

    @Test
    void update_returnsTrueWhenRowUpdated() throws Exception {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(svc.update(jdbc, "slug", "T", "c", "t", Map.of()));
    }

    @Test
    void update_returnsFalseWhenNoRowMatched() throws Exception {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
        assertFalse(svc.update(jdbc, "slug", "T", "c", "t", Map.of()));
    }

    // ---- delete ----------------------------------------------------------

    @Test
    void delete_returnsTrueWhenRowDeleted() {
        when(jdbc.update(anyString(), eq("slug"))).thenReturn(1);
        assertTrue(svc.delete(jdbc, "slug"));
    }

    @Test
    void delete_returnsFalseWhenNoRowMatched() {
        when(jdbc.update(anyString(), eq("missing"))).thenReturn(0);
        assertFalse(svc.delete(jdbc, "missing"));
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

    // ---- sanity check that create path does not re-query on empty ------

    @Test
    void create_noCollision_queriesItemIdExactlyOnce() throws Exception {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        svc.create(jdbc, "Unique Title", "g", "t", Map.of());

        verify(jdbc, times(1)).queryForList(anyString(), anyString());
        verify(jdbc, never()).queryForList(anyString()); // findAll shape not hit
    }
}
