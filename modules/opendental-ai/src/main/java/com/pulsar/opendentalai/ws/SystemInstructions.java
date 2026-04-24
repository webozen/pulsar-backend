package com.pulsar.opendentalai.ws;

import com.pulsar.opendentalai.schema.SchemaCatalog;

/**
 * Prompt builder for the OpenDental AI chat session. Combines a fixed role
 * description with the full schema catalog so Gemini can author correct SQL
 * for the {@code run_opendental_query} tool on every turn.
 */
public final class SystemInstructions {
    private SystemInstructions() {}

    public static String build(SchemaCatalog catalog, String tenantSlug) {
        // Two tiers: a small, fixed policy prose + an optional schema section.
        // Setting INCLUDE_SCHEMA_IN_PROMPT=false gives us a minimal ~500 char prompt
        // so we can verify the Gemini Live + tool-calling loop works before loading
        // the full OpenDental catalog. Default ON.
        boolean includeSchema = !"false".equalsIgnoreCase(
            System.getenv().getOrDefault("OPENDENTAL_AI_INCLUDE_SCHEMA", "true"));

        String policy = """
            You answer questions about the '%s' dental practice by calling
            `run_opendental_query` with a read-only MySQL SELECT against the practice's
            OpenDental database. Summarise results in plain English. Never fabricate
            numbers. If ambiguous, ask ONE clarifying question. Only SELECTs; the
            backend rejects writes. Auto-LIMIT 1000.""".formatted(tenantSlug);

        if (!includeSchema) return policy;
        return policy + "\n\n" + catalog.asPromptContext();
    }
}
