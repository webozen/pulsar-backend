package com.pulsar.content.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.content.service.ContentItemService;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * One-shot admin tool: walk this tenant's content_items rows that are
 * missing an AnythingLLM doc location and push them to the workspace.
 * Useful right after the runbook/contact/training -> chat indexing was
 * wired up — pre-existing rows have NULL anythingllm_doc and would
 * otherwise stay invisible to chat until they're edited.
 *
 * Idempotent: rerunning won't duplicate docs because each push
 * additionally returns a stable location based on the title.
 */
@RestController
@RequestMapping("/api/content/admin")
@RequireModule("content")
public class ContentReindexController {
    private static final Logger log = LoggerFactory.getLogger(ContentReindexController.class);

    private final TenantDataSources tenantDs;
    private final ContentItemService svc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentReindexController(TenantDataSources tenantDs, ContentItemService svc) {
        this.tenantDs = tenantDs;
        this.svc = svc;
    }

    @PostMapping("/reindex-items")
    public Map<String, Object> reindex() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT item_id, title, type, content_data FROM content_items WHERE anythingllm_doc IS NULL");
        int pushed = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String itemId = (String) row.get("item_id");
            String title = (String) row.get("title");
            String type = (String) row.get("type");
            Map<String, Object> data;
            try {
                data = mapper.readValue((String) row.get("content_data"),
                    new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Skipping item {}: invalid JSON ({})", itemId, e.getMessage());
                skipped++;
                continue;
            }
            String doc = svc.pushToWorkspace(t.slug(), type, title, data);
            if (doc == null) { skipped++; continue; }
            jdbc.update("UPDATE content_items SET anythingllm_doc = ? WHERE item_id = ?",
                doc, itemId);
            pushed++;
        }
        return Map.of("scanned", rows.size(), "pushed", pushed, "skipped", skipped);
    }
}
