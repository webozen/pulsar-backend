package com.pulsar.ainotes;

import com.pulsar.ainotes.plaud.*;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ai-notes")
@RequireModule("ai-notes")
public class AiNotesController {

    private final TenantDataSources tenantDs;
    private final PlaudService plaud;

    public AiNotesController(TenantDataSources tenantDs, PlaudService plaud) {
        this.tenantDs = tenantDs;
        this.plaud = plaud;
    }

    @GetMapping("/recordings")
    public List<Recording> listRecordings(
        @RequestParam(name = "window", defaultValue = "24h") Window window,
        @RequestParam(name = "since", required = false) Instant since,
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return plaud.listRecordings(token(), window, since, limit);
    }

    @GetMapping("/recordings/{fileId}/transcript")
    public TranscriptResponse transcript(@PathVariable String fileId) {
        return plaud.getTranscript(token(), fileId);
    }

    @GetMapping("/recordings/{fileId}/summary")
    public SummaryResponse summary(@PathVariable String fileId) {
        return plaud.getSummary(token(), fileId);
    }

    @GetMapping("/feed")
    public List<FeedItem> feed(
        @RequestParam(name = "window", defaultValue = "24h") Window window,
        @RequestParam(name = "since", required = false) Instant since,
        @RequestParam(name = "limit", defaultValue = "200") int limit
    ) {
        return plaud.buildFeed(token(), window, since, limit);
    }

    private String token() {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(TenantContext.require().dbName()));
        return jdbc.queryForList("SELECT plaud_token FROM ai_notes_config WHERE id = 1")
            .stream().findFirst()
            .map(row -> (String) row.get("plaud_token"))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Plaud token not configured — complete onboarding first"));
    }
}
