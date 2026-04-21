package com.pulsar.content.api;

import com.pulsar.content.service.AnythingLlmClient;
import com.pulsar.content.service.ContentItemService;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
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
    private final AnythingLlmClient llm;

    public ContentItemController(TenantDataSources tenantDs, ContentItemService svc, AnythingLlmClient llm) {
        this.tenantDs = tenantDs;
        this.svc = svc;
        this.llm = llm;
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
    public Map<String, Object> create(@RequestBody ItemRequest req) throws Exception {
        JdbcTemplate jdbc = jdbc();
        String category = req.category() != null ? req.category() : "general";
        String type = req.type() != null ? req.type() : "runbook";
        Map<String, Object> data = req.contentData() != null ? req.contentData() : Map.of();
        String itemId = svc.create(jdbc, req.title(), category, type, data);
        pushToLlmIfIndexable(req.title(), data);
        return Map.of("itemId", itemId);
    }

    @PutMapping("/{itemId}")
    public Map<String, Object> update(@PathVariable String itemId, @RequestBody ItemRequest req) throws Exception {
        JdbcTemplate jdbc = jdbc();
        String category = req.category() != null ? req.category() : "general";
        String type = req.type() != null ? req.type() : "runbook";
        Map<String, Object> data = req.contentData() != null ? req.contentData() : Map.of();
        if (!svc.update(jdbc, itemId, req.title(), category, type, data)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        pushToLlmIfIndexable(req.title(), data);
        return Map.of("itemId", itemId);
    }

    /** Only push to AnythingLLM when there's real indexable body text. Each item
     *  type stashes its primary body under a different key (runbooks: "content",
     *  contacts: "runbook", training: "description"). Without this gate, metadata-
     *  only edits (e.g. title change) still pushed empty docs and polluted RAG. */
    private void pushToLlmIfIndexable(String title, Map<String, Object> data) {
        String body = null;
        for (String key : new String[]{"content", "runbook", "description"}) {
            Object v = data.get(key);
            if (v instanceof String s && !s.isBlank()) { body = s; break; }
        }
        if (body == null) return;
        llm.pushTextDocument(TenantContext.require().slug(), title, title + "\n\n" + body);
    }

    @DeleteMapping("/{itemId}")
    public Map<String, Object> delete(@PathVariable String itemId) {
        if (!svc.delete(jdbc(), itemId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("deleted", itemId);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
    }
}
