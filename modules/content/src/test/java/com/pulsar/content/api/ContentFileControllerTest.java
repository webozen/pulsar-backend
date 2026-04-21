package com.pulsar.content.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.content.service.ContentFileService;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.PathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Direct-instantiation tests for ContentFileController. */
class ContentFileControllerTest {

    @TempDir Path tempDir;

    private TenantDataSources tenantDs;
    private ContentFileService svc;
    private ContentFileController controller;

    @BeforeEach
    void setUp() {
        tenantDs = Mockito.mock(TenantDataSources.class);
        svc = Mockito.mock(ContentFileService.class);
        controller = new ContentFileController(tenantDs, svc);

        when(tenantDs.forDb(anyString())).thenReturn(Mockito.mock(DataSource.class));
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("content"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void list_forwardsCategoryFilter() {
        when(svc.findAll(any(), eq("docs"))).thenReturn(List.of(Map.of("fileId", "f1")));

        List<Map<String, Object>> out = controller.list("docs");

        assertEquals(1, out.size());
        verify(svc).findAll(any(), eq("docs"));
    }

    @Test
    void upload_delegatesToService() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "doc.pdf",
            "application/pdf", new byte[]{1, 2});
        when(svc.store(any(), eq("acme"), eq("general"), eq(file)))
            .thenReturn(Map.of("fileId", "fid", "filename", "doc.pdf"));

        Map<String, Object> resp = controller.upload(file, "general");

        assertEquals("fid", resp.get("fileId"));
        assertEquals("doc.pdf", resp.get("filename"));
    }

    @Test
    void download_notFound_throws404() {
        when(svc.getFilePath(any(), eq("missing"))).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.download("missing"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void download_fileMissingOnDisk_throws404() {
        when(svc.getFilePath(any(), eq("fid"))).thenReturn(tempDir.resolve("absent.txt"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.download("fid"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void download_fileExists_returns200WithContentDispositionHeader() throws IOException {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hi");
        when(svc.getFilePath(any(), eq("fid"))).thenReturn(file);

        ResponseEntity<PathResource> resp = controller.download("fid");

        assertEquals(200, resp.getStatusCode().value());
        String cd = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertEquals("attachment; filename=\"hello.txt\"", cd);
    }

    @Test
    void delete_notFound_throws404() {
        when(svc.delete(any(), eq("acme"), eq("missing"))).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.delete("missing"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void delete_success_returnsDeletedId() {
        when(svc.delete(any(), eq("acme"), eq("fid"))).thenReturn(true);
        Map<String, Object> resp = controller.delete("fid");
        assertEquals("fid", resp.get("deleted"));
    }
}
