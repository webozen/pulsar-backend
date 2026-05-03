package com.pulsar.content.api;

import com.pulsar.content.service.ContentItemService;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/content/items")
@RequireModule("content")
public class ContentItemController {
    private final TenantDataSources tenantDs;
    private final ContentItemService svc;

    public ContentItemController(TenantDataSources tenantDs, ContentItemService svc) {
        this.tenantDs = tenantDs;
        this.svc = svc;
    }

    public record ItemRequest(
        @NotBlank String title,
        String category,
        String type,
        Map<String, Object> contentData
    ) {}

    @GetMapping
    public List<Map<String, Object>> list(
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "type", required = false) String type
    ) {
        return svc.findAll(jdbc(), category, type).stream().map(svc::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ItemRequest req) throws Exception {
        String slug = TenantContext.require().slug();
        String category = req.category() != null ? req.category() : "general";
        String type = req.type() != null ? req.type() : "runbook";
        Map<String, Object> data = req.contentData() != null ? req.contentData() : Map.of();
        String itemId = svc.create(jdbc(), slug, req.title(), category, type, data);
        return Map.of("itemId", itemId);
    }

    @PutMapping("/{itemId}")
    public Map<String, Object> update(@PathVariable String itemId, @Valid @RequestBody ItemRequest req) throws Exception {
        String slug = TenantContext.require().slug();
        String category = req.category() != null ? req.category() : "general";
        String type = req.type() != null ? req.type() : "runbook";
        Map<String, Object> data = req.contentData() != null ? req.contentData() : Map.of();
        if (!svc.update(jdbc(), slug, itemId, req.title(), category, type, data)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Map.of("itemId", itemId);
    }

    @DeleteMapping("/{itemId}")
    public Map<String, Object> delete(@PathVariable String itemId) {
        String slug = TenantContext.require().slug();
        if (!svc.delete(jdbc(), slug, itemId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("deleted", itemId);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
