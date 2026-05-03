package com.pulsar.content.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContentItemFormatter}. The exact whitespace
 * shape isn't load-bearing for retrieval (AnythingLLM tokenizes), but
 * <em>field labels</em> are — embeddings learn the (label, value)
 * association and downstream queries like "what's John's phone" rely on
 * the literal label text appearing alongside the value. So these tests
 * pin labels, not formatting.
 */
class ContentItemFormatterTest {

    @Test
    void runbook_includesContentAndStepsAndTags() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", "Reset the printer queue when jobs hang.");
        data.put("steps", List.of("Open Settings", "Click Printers", "Right-click and Restart"));
        data.put("tags", List.of("ops", "printer"));
        data.put("priority", "high");

        String out = ContentItemFormatter.format("runbook", "Printer hang fix", data);

        assertTrue(out.startsWith("Printer hang fix\nType: Guide\n"));
        assertTrue(out.contains("Priority: high"));
        assertTrue(out.contains("Tags: ops, printer"));
        assertTrue(out.contains("Reset the printer queue when jobs hang."));
        assertTrue(out.contains("1. Open Settings"));
        assertTrue(out.contains("2. Click Printers"));
        assertTrue(out.contains("3. Right-click and Restart"));
    }

    @Test
    void contact_putsPhoneAndEmailNearTheTop_andEachOnLabeledLine() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", "+1-800-555-1234");
        data.put("email", "billing@stripe.com");
        data.put("notes", "First-tier billing support.");

        String out = ContentItemFormatter.format("contact", "Stripe Billing", data);

        assertTrue(out.contains("Type: Support Contact"));
        assertTrue(out.contains("Phone: +1-800-555-1234"));
        assertTrue(out.contains("Email: billing@stripe.com"));
        assertTrue(out.contains("Notes:\nFirst-tier billing support."));
    }

    @Test
    void training_includesDescriptionAndDurationAndRequiredFor() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("description", "Onboarding walkthrough for new front-desk staff.");
        data.put("duration", "45m");
        data.put("requiredFor", List.of("front-desk", "manager"));

        String out = ContentItemFormatter.format("training", "Front desk 101", data);

        assertTrue(out.contains("Type: Training Resource"));
        assertTrue(out.contains("Duration: 45m"));
        assertTrue(out.contains("Required for: front-desk, manager"));
        assertTrue(out.contains("Onboarding walkthrough for new front-desk staff."));
    }

    @Test
    void blankFieldsAreSkipped_notRenderedAsLabelColon() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", "");
        data.put("email", "  ");
        data.put("notes", "Real notes here.");

        String out = ContentItemFormatter.format("contact", "Empty Contact", data);

        assertFalse(out.contains("Phone:"), "blank phone must not produce a label line");
        assertFalse(out.contains("Email:"), "whitespace-only email must not produce a label line");
        assertTrue(out.contains("Real notes here."));
    }

    @Test
    void unknownType_dumpsKeysAsFallback_soNothingIsLost() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("custom_field", "value-A");
        data.put("another", "value-B");

        String out = ContentItemFormatter.format("unknown_type", "Untyped", data);

        assertTrue(out.contains("Type: unknown_type"));
        assertTrue(out.contains("custom_field: value-A"));
        assertTrue(out.contains("another: value-B"));
    }

    @Test
    void allBlank_producesOnlyHeader_soServiceCanSkipPushing() {
        // The service uses "lines() <= 2" as the empty-doc signal:
        // title + Type line means nothing useful to embed.
        String out = ContentItemFormatter.format("contact", "Nothing", Map.of());
        assertEquals(2, out.lines().count());
        assertTrue(out.startsWith("Nothing\nType: Support Contact"));
    }
}
