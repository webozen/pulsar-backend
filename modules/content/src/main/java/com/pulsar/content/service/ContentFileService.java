package com.pulsar.content.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ContentFileService {
    private final String uploadDir;
    private final AnythingLlmClient llm;

    public ContentFileService(
        @Value("${pulsar.content.upload-dir:./uploads}") String uploadDir,
        AnythingLlmClient llm
    ) {
        this.uploadDir = uploadDir;
        this.llm = llm;
    }

    public Map<String, Object> store(JdbcTemplate jdbc, String tenantSlug,
                                      String category, MultipartFile file) throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path dir = Paths.get(uploadDir, tenantSlug);
        Files.createDirectories(dir);
        String storedName = fileId + "_" + file.getOriginalFilename();
        Path dest = dir.resolve(storedName);
        Files.write(dest, file.getBytes());

        String ct = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String llmDoc = llm.uploadFile(tenantSlug, file.getOriginalFilename(), ct, file.getBytes());

        jdbc.update(
            "INSERT INTO content_files (file_id, filename, content_type, file_path, file_size, category, anythingllm_doc) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            fileId, file.getOriginalFilename(), ct, dest.toString(), file.getSize(), category, llmDoc
        );
        return Map.of("fileId", fileId, "filename", file.getOriginalFilename());
    }

    public List<Map<String, Object>> findAll(JdbcTemplate jdbc, String category) {
        String sql = category != null
            ? "SELECT * FROM content_files WHERE category = ? ORDER BY created_at DESC"
            : "SELECT * FROM content_files ORDER BY created_at DESC";
        Object[] args = category != null ? new Object[]{category} : new Object[0];
        return jdbc.queryForList(sql, args).stream().map(row -> Map.<String, Object>of(
            "fileId",      row.get("file_id"),
            "filename",    row.get("filename"),
            "contentType", row.get("content_type"),
            "fileSize",    row.get("file_size"),
            "category",    row.get("category"),
            "createdAt",   row.get("created_at").toString()
        )).toList();
    }

    public boolean delete(JdbcTemplate jdbc, String tenantSlug, String fileId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM content_files WHERE file_id = ?", fileId);
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.get(0);
        String llmDoc = (String) row.get("anythingllm_doc");
        if (llmDoc != null) llm.removeDocument(tenantSlug, llmDoc);
        try { Files.deleteIfExists(Paths.get((String) row.get("file_path"))); } catch (IOException ignored) {}
        jdbc.update("DELETE FROM content_files WHERE file_id = ?", fileId);
        return true;
    }

    public Path getFilePath(JdbcTemplate jdbc, String fileId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT file_path FROM content_files WHERE file_id = ?", fileId);
        if (rows.isEmpty()) return null;
        return Paths.get((String) rows.get(0).get("file_path"));
    }
}
