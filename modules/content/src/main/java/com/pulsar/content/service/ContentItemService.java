package com.pulsar.content.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ContentItemService {
    private final ObjectMapper mapper = new ObjectMapper();

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

    public String create(JdbcTemplate jdbc, String title, String category, String type,
                         Map<String, Object> contentData) throws Exception {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String itemId = slug;
        int counter = 1;
        while (findByItemId(jdbc, itemId).isPresent()) {
            itemId = slug + "-" + (++counter);
        }
        jdbc.update(
            "INSERT INTO content_items (item_id, title, category, type, content_data) VALUES (?, ?, ?, ?, ?)",
            itemId, title, category, type, mapper.writeValueAsString(contentData)
        );
        return itemId;
    }

    public boolean update(JdbcTemplate jdbc, String itemId, String title, String category,
                          String type, Map<String, Object> contentData) throws Exception {
        int rows = jdbc.update(
            "UPDATE content_items SET title=?, category=?, type=?, content_data=? WHERE item_id=?",
            title, category, type, mapper.writeValueAsString(contentData), itemId
        );
        return rows > 0;
    }

    public boolean delete(JdbcTemplate jdbc, String itemId) {
        return jdbc.update("DELETE FROM content_items WHERE item_id = ?", itemId) > 0;
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
