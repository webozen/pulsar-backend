package com.pulsar.translate.history;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/translate/conversations")
@RequireModule("translate")
public class TranslateConversationsController {

    private final HistoryService history;

    public TranslateConversationsController(HistoryService history) {
        this.history = history;
    }

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        String dbName = TenantContext.require().dbName();
        List<Map<String, Object>> rows = history.repo().list(dbName, safeLimit, safeOffset, includeDeleted);
        return Map.of(
            "items", rows,
            "limit", safeLimit,
            "offset", safeOffset,
            "historyEnabled", history.settings().forDb(dbName).historyEnabled() && history.isAvailable()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        String dbName = TenantContext.require().dbName();
        Map<String, Object> row = history.repo().findById(dbName, id);
        if (row == null) return ResponseEntity.notFound().build();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("startedAt", row.get("started_at"));
        result.put("endedAt", row.get("ended_at"));
        result.put("sourceLang", row.get("source_lang"));
        result.put("targetLang", row.get("target_lang"));
        result.put("mode", row.get("mode"));
        result.put("extendsUsed", row.get("extends_used"));
        result.put("deletedAt", row.get("deleted_at"));
        result.put("transcript", history.decryptTranscript(row));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> softDelete(@PathVariable long id) {
        String dbName = TenantContext.require().dbName();
        boolean ok = history.repo().softDelete(dbName, id);
        return ok ? ResponseEntity.ok(Map.of("deleted", true))
                  : ResponseEntity.notFound().build();
    }
}
