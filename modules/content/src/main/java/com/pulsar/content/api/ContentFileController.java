package com.pulsar.content.api;

import com.pulsar.content.service.ContentFileService;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.core.io.PathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/content/files")
@RequireModule("content")
public class ContentFileController {
    private final TenantDataSources tenantDs;
    private final ContentFileService svc;

    public ContentFileController(TenantDataSources tenantDs, ContentFileService svc) {
        this.tenantDs = tenantDs;
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String category) {
        return svc.findAll(jdbc(), category);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "category", defaultValue = "general") String category
    ) throws Exception {
        var t = TenantContext.require();
        return svc.store(jdbc(), t.slug(), category, file);
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<PathResource> download(@PathVariable String fileId) {
        Path path = svc.getFilePath(jdbc(), fileId);
        if (path == null || !path.toFile().exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
            .body(new PathResource(path));
    }

    @DeleteMapping("/{fileId}")
    public Map<String, Object> delete(@PathVariable String fileId) {
        String slug = TenantContext.require().slug();
        if (!svc.delete(jdbc(), slug, fileId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("deleted", fileId);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
