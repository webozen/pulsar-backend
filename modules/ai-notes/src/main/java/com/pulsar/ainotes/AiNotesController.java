package com.pulsar.ainotes;

import com.pulsar.ainotes.plaud.*;
import com.pulsar.kernel.credentials.PlaudKeyResolver;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ai-notes")
@RequireModule("ai-notes")
public class AiNotesController {

    private final PlaudService plaud;
    private final PlaudKeyResolver plaudKeyResolver;

    public AiNotesController(PlaudService plaud, PlaudKeyResolver plaudKeyResolver) {
        this.plaud = plaud;
        this.plaudKeyResolver = plaudKeyResolver;
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
        String t = plaudKeyResolver.resolveForDb(TenantContext.require().dbName());
        if (t == null || t.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Plaud token not configured — set it in workspace Settings");
        }
        return t;
    }
}
