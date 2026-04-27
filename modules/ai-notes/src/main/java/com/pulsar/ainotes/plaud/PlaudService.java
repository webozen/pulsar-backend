package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PlaudService {

    private final String baseUrl;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PlaudService(
        @Value("${pulsar.plaud.base-url:https://api.plaud.ai}") String baseUrl,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<Recording> listRecordings(String plaudToken, Window window, Instant since, int limit) {
        PlaudClient client = client(plaudToken);
        List<Map<String, Object>> raw = client.listRecordings(limit, 0);
        List<Recording> all = raw.stream()
            .map(Recording::fromPlaud)
            .toList();
        Instant cutoff = cutoff(window, since);
        if (cutoff == null) return all;
        return all.stream()
            .filter(r -> r.createdAt() == null || !r.createdAt().isBefore(cutoff))
            .toList();
    }

    public TranscriptResponse getTranscript(String plaudToken, String fileId) {
        Map<String, Object> raw = client(plaudToken).getFileRaw(fileId);
        return transcriptFromRaw(fileId, raw);
    }

    public SummaryResponse getSummary(String plaudToken, String fileId) {
        Map<String, Object> raw = client(plaudToken).getFileRaw(fileId);
        return summaryFromRaw(fileId, raw);
    }

    public List<FeedItem> buildFeed(String plaudToken, Window window, Instant since, int limit) {
        PlaudClient client = client(plaudToken);
        List<Map<String, Object>> rawList = client.listRecordings(limit, 0);
        Instant cutoff = cutoff(window, since);
        List<FeedItem> out = new ArrayList<>();
        for (Map<String, Object> rawMeta : rawList) {
            Recording rec = Recording.fromPlaud(rawMeta);
            if (cutoff != null && rec.createdAt() != null && rec.createdAt().isBefore(cutoff)) continue;
            TranscriptResponse transcript = null;
            SummaryResponse summary = null;
            if (rec.hasTranscription() || rec.hasSummary()) {
                Map<String, Object> raw = client.getFileRaw(rec.id());
                if (rec.hasTranscription()) transcript = transcriptFromRaw(rec.id(), raw);
                if (rec.hasSummary()) summary = summaryFromRaw(rec.id(), raw);
            }
            out.add(new FeedItem(rec, transcript, summary));
        }
        return out;
    }

    private PlaudClient client(String token) {
        return new PlaudClient(baseUrl, token, restClient, objectMapper);
    }

    private Instant cutoff(Window window, Instant since) {
        if (since != null) return since;
        if (window == null || window == Window.ALL) return null;
        return Optional.ofNullable(window.duration().orElse(null))
            .map(d -> Instant.now().minus(d))
            .orElse(null);
    }

    private TranscriptResponse transcriptFromRaw(String fileId, Map<String, Object> raw) {
        Object trans = raw.get("trans_result");
        List<Object> segments = trans instanceof List<?> l
            ? objectMapper.convertValue(l, new TypeReference<List<Object>>() {})
            : List.of();
        return new TranscriptResponse(fileId, segments);
    }

    private SummaryResponse summaryFromRaw(String fileId, Map<String, Object> raw) {
        Object ai = raw.get("ai_content");
        String content = ai == null ? "" : ai.toString();
        if (content.isEmpty()) {
            Object sl = raw.get("summary_list");
            if (sl instanceof List<?> l && !l.isEmpty()) content = String.valueOf(l.get(0));
        }
        return new SummaryResponse(fileId, content);
    }
}
