package com.pulsar.opendentalai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the pre-parsed OpenDental schema catalog (generated from the published
 * XML by {@code tools/parse-od-xml.py}) and exposes a flattened system-prompt
 * section that Gemini uses to pick correct table/column names when authoring
 * SQL for {@code run_opendental_query}.
 *
 * <p>The full catalog is ~1.3 MB / 427 tables — small enough to inline into
 * every session's system prompt for the first cut. If that becomes costly we
 * switch to RAG (embed each table's summary, retrieve top-K per question).
 */
@Component
public class SchemaCatalog {
    private JsonNode root;
    private String flattened;

    @PostConstruct
    public void load() throws IOException {
        ClassPathResource res = new ClassPathResource("schema/opendental-26.1.json");
        try (InputStream in = res.getInputStream()) {
            root = new ObjectMapper().readTree(in);
        }
        flattened = buildFlattenedSummary(root);
    }

    public String version() { return root.path("version").asText("unknown"); }
    public int tableCount() { return root.path("table_count").asInt(0); }

    /** A prompt-ready schema description. One line per column, grouped by table. */
    public String asPromptContext() { return flattened; }

    /**
     * Tables we fully detail (name + every column) in the system prompt.
     * These cover the vast majority of practice-management questions.
     * The rest are listed by name + one-line summary only — Gemini can still
     * reference them, and a follow-up version can fetch full columns on demand.
     */
    private static final java.util.Set<String> CORE_TABLES = java.util.Set.of(
        "patient", "appointment", "appointmenttype", "procedurelog", "procedurecode",
        "recall", "recalltype", "provider", "clinic", "operatory",
        "payment", "paysplit", "adjustment", "claim", "claimproc",
        "insplan", "inssub", "patplan", "benefit", "carrier",
        "treatplan", "treatplanattach", "task", "commlog", "statement",
        "definition", "preference"
    );

    private static String buildFlattenedSummary(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        sb.append("# OpenDental schema v").append(root.path("version").asText())
          .append(" (").append(root.path("table_count").asInt()).append(" tables total)\n\n");

        sb.append("## Core tables (fully detailed)\n");
        sb.append("These are the tables you'll most often need.\n\n");
        for (JsonNode t : root.path("tables")) {
            String name = t.path("name").asText();
            if (!CORE_TABLES.contains(name)) continue;
            appendDetailedTable(sb, t);
        }

        sb.append("\n## Other tables (names + summaries only)\n");
        sb.append("If a question needs one of these, compose a SELECT against it by name.\n");
        sb.append("If you need specific column names, write SELECT * LIMIT 1 first, then refine.\n\n");
        for (JsonNode t : root.path("tables")) {
            String name = t.path("name").asText();
            if (CORE_TABLES.contains(name)) continue;
            String summary = t.path("summary").asText("");
            sb.append("- `").append(name).append("`");
            if (!summary.isEmpty()) sb.append(" — ").append(summary);
            sb.append("\n");
        }

        return sb.toString();
    }

    private static void appendDetailedTable(StringBuilder sb, JsonNode t) {
        sb.append("### ").append(t.path("name").asText()).append("\n");
        String summary = t.path("summary").asText("");
        if (!summary.isEmpty()) sb.append(summary).append("\n");
        for (JsonNode c : t.path("columns")) {
            sb.append("  - ")
              .append(c.path("name").asText())
              .append(" ").append(c.path("type").asText());
            String colSum = c.path("summary").asText("");
            if (!colSum.isEmpty() && !".".equals(colSum)) sb.append(" — ").append(colSum);
            JsonNode enumNode = c.path("enum");
            if (enumNode.isObject()) {
                sb.append(" enum(");
                boolean first = true;
                for (JsonNode v : enumNode.path("values")) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(v.path("value").asText()).append("=").append(v.path("name").asText());
                }
                sb.append(")");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }
}
