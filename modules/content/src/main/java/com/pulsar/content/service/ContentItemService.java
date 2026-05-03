package com.pulsar.content.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the full lifecycle of a content_items row, including its mirror
 * in the AnythingLLM workspace so chat retrieval covers guides, contacts,
 * and training rows — not just file uploads.
 *
 * Lifecycle invariants the create/update/delete methods preserve:
 *   - Each row's {@code anythingllm_doc} column is the doc location
 *     currently in the workspace, or NULL if the row body has no
 *     indexable text (e.g. a contact saved with all fields blank).
 *   - On update, we delete the previous doc BEFORE pushing the new one
 *     so retrieval never returns stale or duplicated content for the
 *     same item.
 *   - On delete, we remove the doc before removing the row.
 *
 * AnythingLLM I/O is best-effort: a failure to push doesn't fail the
 * write (the row is still saved correctly) but is logged so the
 * backfill endpoint can pick it up later.
 */
@Service
public class ContentItemService {
    private static final Logger log = LoggerFactory.getLogger(ContentItemService.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnythingLlmClient llm;

    public ContentItemService(AnythingLlmClient llm) {
        this.llm = llm;
    }

    public List<Map<String, Object>> findAll(JdbcTemplate jdbc, String category, String type) {
        if (category != null && type != null) {
            return jdbc.queryForList(
                "SELECT * FROM content_items WHERE category = ? AND type = ? ORDER BY created_at DESC",
                category, type);
        } else if (category != null) {
            return jdbc.queryForList(
                "SELECT * FROM content_items WHERE category = ? ORDER BY created_at DESC", category);
        } else if (type != null) {
            return jdbc.queryForList(
                "SELECT * FROM content_items WHERE type = ? ORDER BY created_at DESC", type);
        }
        return jdbc.queryForList("SELECT * FROM content_items ORDER BY created_at DESC");
    }

    public Optional<Map<String, Object>> findByItemId(JdbcTemplate jdbc, String itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM content_items WHERE item_id = ?", itemId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public String create(JdbcTemplate jdbc, String tenantSlug, String title, String category,
                         String type, Map<String, Object> contentData) throws Exception {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String itemId = slug;
        int counter = 1;
        while (findByItemId(jdbc, itemId).isPresent()) {
            itemId = slug + "-" + (++counter);
        }
        String llmDoc = pushToWorkspace(tenantSlug, type, title, contentData);
        jdbc.update(
            "INSERT INTO content_items (item_id, title, category, type, content_data, anythingllm_doc) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            itemId, title, category, type, mapper.writeValueAsString(contentData), llmDoc
        );
        return itemId;
    }

    public boolean update(JdbcTemplate jdbc, String tenantSlug, String itemId, String title,
                          String category, String type, Map<String, Object> contentData) throws Exception {
        Optional<Map<String, Object>> existing = findByItemId(jdbc, itemId);
        if (existing.isEmpty()) return false;
        // Remove the previous doc first so retrieval never returns stale
        // or duplicated content for this item.
        Object oldDoc = existing.get().get("anythingllm_doc");
        if (oldDoc instanceof String s && !s.isBlank()) {
            try { llm.removeDocument(tenantSlug, s); }
            catch (Exception e) { log.warn("Failed to remove old AnythingLLM doc for {}: {}", itemId, e.getMessage()); }
        }
        String llmDoc = pushToWorkspace(tenantSlug, type, title, contentData);
        int rows = jdbc.update(
            "UPDATE content_items SET title = ?, category = ?, type = ?, content_data = ?, "
                + "anythingllm_doc = ? WHERE item_id = ?",
            title, category, type, mapper.writeValueAsString(contentData), llmDoc, itemId
        );
        return rows > 0;
    }

    public boolean delete(JdbcTemplate jdbc, String tenantSlug, String itemId) {
        Optional<Map<String, Object>> existing = findByItemId(jdbc, itemId);
        if (existing.isEmpty()) return false;
        Object doc = existing.get().get("anythingllm_doc");
        if (doc instanceof String s && !s.isBlank()) {
            try { llm.removeDocument(tenantSlug, s); }
            catch (Exception e) { log.warn("Failed to remove AnythingLLM doc for {}: {}", itemId, e.getMessage()); }
        }
        return jdbc.update("DELETE FROM content_items WHERE item_id = ?", itemId) > 0;
    }

    /**
     * Push (or re-push) a single row's body into the tenant's workspace.
     * Public so the backfill admin endpoint can call it for legacy rows
     * that were written before this lifecycle was wired up.
     *
     * @return the AnythingLLM doc location to store, or null if AnythingLLM
     *   isn't configured / the body has no indexable text / the push failed.
     */
    public String pushToWorkspace(String tenantSlug, String type, String title,
                                   Map<String, Object> contentData) {
        if (!llm.isConfigured()) return null;
        String body = ContentItemFormatter.format(type, title, contentData);
        // If the formatter produced only the header (title + type line)
        // there's no real content to embed — skip the push so we don't
        // pollute retrieval with empty docs.
        if (body == null || body.lines().count() <= 2) return null;
        try {
            return llm.pushTextDocument(tenantSlug, title, body);
        } catch (Exception e) {
            log.warn("Failed to push content item '{}' to AnythingLLM: {}", title, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> toResponse(Map<String, Object> row) {
        try {
            String json = (String) row.get("content_data");
            Map<String, Object> data = mapper.readValue(json, new TypeReference<>() {});
            data.put("itemId", row.get("item_id"));
            data.put("title", row.get("title"));
            data.put("category", row.get("category"));
            data.put("type", row.get("type"));
            data.put("createdAt", row.get("created_at").toString());
            return data;
        } catch (Exception e) {
            return Map.of("itemId", row.get("item_id"), "error", "Invalid data");
        }
    }
}
