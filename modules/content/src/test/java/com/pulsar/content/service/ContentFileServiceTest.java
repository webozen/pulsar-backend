package com.pulsar.content.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** Unit tests for ContentFileService; uses @TempDir for disk side-effects, mocks JDBC and LLM. */
class ContentFileServiceTest {

    @TempDir Path tempDir;

    private JdbcTemplate jdbc;
    private AnythingLlmClient llm;
    private ContentFileService svc;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        llm = Mockito.mock(AnythingLlmClient.class);
        svc = new ContentFileService(tempDir.toString(), llm);
    }

    // ---- store -----------------------------------------------------------

    @Test
    void store_writesFileToDiskAndInsertsDbRow() throws IOException {
        MultipartFile file = new MockMultipartFile(
            "file", "hello.txt", "text/plain", "body".getBytes());
        when(llm.uploadFile(eq("acme"), eq("hello.txt"), eq("text/plain"), any()))
            .thenReturn("custom-documents/hello.txt-hash.json");

        Map<String, Object> out = svc.store(jdbc, "acme", "general", file);

        assertNotNull(out.get("fileId"));
        assertEquals("hello.txt", out.get("filename"));

        // File should exist on disk under <tempDir>/acme/<fileId>_hello.txt.
        String fileId = (String) out.get("fileId");
        Path expected = tempDir.resolve("acme").resolve(fileId + "_hello.txt");
        assertTrue(Files.exists(expected), "expected file on disk at " + expected);
        assertEquals("body", Files.readString(expected));

        // DB insert called with 7 args.
        ArgumentCaptor<Object> a1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a4 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a5 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a6 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> a7 = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(),
            a1.capture(), a2.capture(), a3.capture(), a4.capture(),
            a5.capture(), a6.capture(), a7.capture());
        assertEquals(fileId, a1.getValue());
        assertEquals("hello.txt", a2.getValue());
        assertEquals("text/plain", a3.getValue());
        assertEquals(expected.toString(), a4.getValue());
        assertEquals(4L, a5.getValue()); // file size
        assertEquals("general", a6.getValue());
        assertEquals("custom-documents/hello.txt-hash.json", a7.getValue());
    }

    @Test
    void store_missingContentType_defaultsToOctetStream() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "x.bin", null, new byte[]{1, 2, 3});

        svc.store(jdbc, "acme", "general", file);

        verify(llm).uploadFile(eq("acme"), eq("x.bin"), eq("application/octet-stream"), any());
    }

    // ---- findAll ---------------------------------------------------------

    @Test
    void findAll_noCategory_queriesAll() {
        Map<String, Object> row = baseRow();
        // The service passes a zero-length Object[] as varargs, so match both the
        // direct sql-only and sql+empty-varargs call shapes.
        when(jdbc.queryForList(eq("SELECT * FROM content_files ORDER BY created_at DESC"),
            any(Object[].class))).thenReturn(List.of(row));
        when(jdbc.queryForList("SELECT * FROM content_files ORDER BY created_at DESC"))
            .thenReturn(List.of(row));

        List<Map<String, Object>> out = svc.findAll(jdbc, null);

        assertEquals(1, out.size());
        Map<String, Object> resp = out.get(0);
        assertEquals("fid", resp.get("fileId"));
        assertEquals("doc.pdf", resp.get("filename"));
        assertEquals("application/pdf", resp.get("contentType"));
        assertEquals(42L, resp.get("fileSize"));
        assertEquals("general", resp.get("category"));
        assertNotNull(resp.get("createdAt"));
    }

    @Test
    void findAll_withCategory_usesFilteredQuery() {
        when(jdbc.queryForList(anyString(), eq("docs"))).thenReturn(List.of(baseRow()));

        svc.findAll(jdbc, "docs");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq("docs"));
        assertTrue(sql.getValue().contains("category = ?"));
    }

    // ---- delete ----------------------------------------------------------

    @Test
    void delete_missingRow_returnsFalseAndDoesNothing() {
        when(jdbc.queryForList(anyString(), eq("nope"))).thenReturn(List.of());

        boolean deleted = svc.delete(jdbc, "acme", "nope");

        assertFalse(deleted);
        verify(llm, never()).removeDocument(anyString(), anyString());
        verify(jdbc, never()).update(anyString(), eq("nope"));
    }

    @Test
    void delete_existingRow_removesFromDiskDbAndLlm() throws IOException {
        Path file = tempDir.resolve("to-delete.txt");
        Files.writeString(file, "bye");

        Map<String, Object> row = new HashMap<>();
        row.put("file_id", "fid");
        row.put("file_path", file.toString());
        row.put("anythingllm_doc", "custom-documents/abc.json");
        when(jdbc.queryForList(anyString(), eq("fid"))).thenReturn(List.of(row));
        when(jdbc.update(anyString(), eq("fid"))).thenReturn(1);

        boolean deleted = svc.delete(jdbc, "acme", "fid");

        assertTrue(deleted);
        assertFalse(Files.exists(file), "file should be deleted from disk");
        verify(llm).removeDocument("acme", "custom-documents/abc.json");
        verify(jdbc).update("DELETE FROM content_files WHERE file_id = ?", "fid");
    }

    @Test
    void delete_nullLlmDoc_skipsLlmCall() throws IOException {
        Path file = tempDir.resolve("orphan.txt");
        Files.writeString(file, "x");

        Map<String, Object> row = new HashMap<>();
        row.put("file_id", "fid");
        row.put("file_path", file.toString());
        row.put("anythingllm_doc", null);
        when(jdbc.queryForList(anyString(), eq("fid"))).thenReturn(List.of(row));

        svc.delete(jdbc, "acme", "fid");

        verify(llm, never()).removeDocument(anyString(), anyString());
    }

    @Test
    void delete_swallowsIoErrorWhenDiskFileMissing() {
        // file_path points to something that does not exist — deleteIfExists returns false
        // and the exception is swallowed per the service contract.
        Map<String, Object> row = new HashMap<>();
        row.put("file_id", "fid");
        row.put("file_path", tempDir.resolve("does-not-exist").toString());
        row.put("anythingllm_doc", "doc");
        when(jdbc.queryForList(anyString(), eq("fid"))).thenReturn(List.of(row));
        when(jdbc.update(anyString(), eq("fid"))).thenReturn(1);

        assertTrue(svc.delete(jdbc, "acme", "fid"));
    }

    // ---- getFilePath -----------------------------------------------------

    @Test
    void getFilePath_returnsNullWhenMissing() {
        when(jdbc.queryForList(anyString(), eq("nope"))).thenReturn(List.of());
        assertNull(svc.getFilePath(jdbc, "nope"));
    }

    @Test
    void getFilePath_returnsPathFromRow() {
        Map<String, Object> row = Map.of("file_path", tempDir.resolve("x").toString());
        when(jdbc.queryForList(anyString(), eq("fid"))).thenReturn(List.of(row));

        Path p = svc.getFilePath(jdbc, "fid");

        assertNotNull(p);
        assertEquals(tempDir.resolve("x"), p);
    }

    // ---- helpers ---------------------------------------------------------

    private Map<String, Object> baseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("file_id", "fid");
        row.put("filename", "doc.pdf");
        row.put("content_type", "application/pdf");
        row.put("file_size", 42L);
        row.put("category", "general");
        row.put("created_at", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return row;
    }
}
