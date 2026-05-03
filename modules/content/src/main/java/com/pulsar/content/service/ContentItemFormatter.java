package com.pulsar.content.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Render a content_items row into the plain-text doc that AnythingLLM
 * embeds for chat retrieval. Each type lives in {@code content_data}
 * with a different shape (see modules-fe/content-ui/src/{Guides,
 * SupportContacts,Training}Tab.tsx for the source-of-truth field set):
 *
 * <ul>
 *   <li><b>runbook</b> (Guides): content, steps[], priority?, tags[]?, videoUrl?</li>
 *   <li><b>contact</b> (SupportContacts): phone, email, website, notes,
 *       accountId, accountUsername, portal, runbook, priority</li>
 *   <li><b>training</b> (Training): description, videoUrl?, linkUrl?,
 *       duration?, tags[]?, requiredFor[]?</li>
 * </ul>
 *
 * The output is shaped for retrieval-quality, not display: we lead with
 * the title and the type so chunks land near user queries like "where is
 * the guide on X" or "who do we call for billing", then list each field
 * on its own line with a labeled prefix so the embedding picks up
 * (label, value) pairs as semantically related.
 */
public final class ContentItemFormatter {
    private ContentItemFormatter() {}

    public static String format(String type, String title, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append("Type: ").append(humanType(type)).append('\n');
        switch (type) {
            case "runbook" -> appendRunbook(sb, data);
            case "contact" -> appendContact(sb, data);
            case "training" -> appendTraining(sb, data);
            default -> appendGeneric(sb, data);
        }
        return sb.toString().stripTrailing();
    }

    private static void appendRunbook(StringBuilder sb, Map<String, Object> data) {
        appendIfPresent(sb, "Priority", data.get("priority"));
        appendListIfPresent(sb, "Tags", data.get("tags"));
        appendIfPresent(sb, "Video", data.get("videoUrl"));
        appendBlock(sb, "Content", data.get("content"));
        appendNumberedSteps(sb, data.get("steps"));
    }

    private static void appendContact(StringBuilder sb, Map<String, Object> data) {
        appendIfPresent(sb, "Phone", data.get("phone"));
        appendIfPresent(sb, "Email", data.get("email"));
        appendIfPresent(sb, "Website", data.get("website"));
        appendIfPresent(sb, "Portal", data.get("portal"));
        appendIfPresent(sb, "Account ID", data.get("accountId"));
        appendIfPresent(sb, "Account username", data.get("accountUsername"));
        appendIfPresent(sb, "Priority", data.get("priority"));
        appendBlock(sb, "Notes", data.get("notes"));
        appendBlock(sb, "Runbook / when to call", data.get("runbook"));
    }

    private static void appendTraining(StringBuilder sb, Map<String, Object> data) {
        appendIfPresent(sb, "Duration", data.get("duration"));
        appendIfPresent(sb, "Video", data.get("videoUrl"));
        appendIfPresent(sb, "Link", data.get("linkUrl"));
        appendListIfPresent(sb, "Tags", data.get("tags"));
        appendListIfPresent(sb, "Required for", data.get("requiredFor"));
        appendBlock(sb, "Description", data.get("description"));
    }

    /** Fallback for unknown types — dump the JSON body verbatim so we never
     *  silently lose searchable content if a new type is introduced. */
    private static void appendGeneric(StringBuilder sb, Map<String, Object> data) {
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getValue() == null) continue;
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
    }

    // ─── primitives ──────────────────────────────────────────────────────

    private static void appendIfPresent(StringBuilder sb, String label, Object v) {
        if (v == null) return;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return;
        sb.append(label).append(": ").append(s).append('\n');
    }

    private static void appendListIfPresent(StringBuilder sb, String label, Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) return;
        List<String> kept = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) kept.add(s);
        }
        if (kept.isEmpty()) return;
        sb.append(label).append(": ").append(String.join(", ", kept)).append('\n');
    }

    private static void appendBlock(StringBuilder sb, String label, Object v) {
        if (v == null) return;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return;
        sb.append('\n').append(label).append(":\n").append(s).append('\n');
    }

    private static void appendNumberedSteps(StringBuilder sb, Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) return;
        sb.append("\nSteps:\n");
        int n = 1;
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) continue;
            sb.append(n++).append(". ").append(s).append('\n');
        }
    }

    private static String humanType(String type) {
        return switch (type) {
            case "runbook" -> "Guide";
            case "contact" -> "Support Contact";
            case "training" -> "Training Resource";
            default -> type;
        };
    }
}
