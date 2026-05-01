package com.pulsar.translate.ws;

import com.ibm.icu.text.Transliterator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side fallback that converts input transcripts to the native script of
 * the speaker's language. Runs after Gemini's STT pass and only when the source
 * language is known. Handles two failure modes Gemini Live exhibits:
 *
 *   1. Romanization — "Hanji ki haal chal" instead of "ਹਾਂਜੀ ਕੀ ਹਾਲ ਚਾਲ".
 *   2. Wrong-script — Devanagari output for a Punjabi speaker, or vice-versa,
 *      because Gemini's STT defaults to the dominant Indic script in its
 *      training data regardless of the language hint we passed.
 *
 * We pick an ICU transliterator dynamically based on the detected source
 * script of each frame and the configured target script for the language.
 * ICU's Indic-Indic transliterators go through an InterIndic intermediate,
 * so quality is high and lossless for the script-swap case.
 *
 * Quality caveat for the romanization case: ICU's Latin-* transliterators
 * assume ISO-15919-style romanization. Gemini's casual romanization produces
 * readable but occasionally imprecise tone marks / nasalization compared to
 * a hand-typed native-script string. Acceptable as a backstop.
 */
public final class TranscriptTransliterator {

    /** ISO 639-1 → ICU script name we want the transcript rendered in. */
    private static final Map<String, String> TARGET_SCRIPT = Map.ofEntries(
        Map.entry("pa", "Gurmukhi"),
        Map.entry("hi", "Devanagari"),
        Map.entry("mr", "Devanagari"),
        Map.entry("ne", "Devanagari"),
        Map.entry("sa", "Devanagari"),
        Map.entry("ta", "Tamil"),
        Map.entry("te", "Telugu"),
        Map.entry("kn", "Kannada"),
        Map.entry("ml", "Malayalam"),
        Map.entry("bn", "Bengali"),
        Map.entry("gu", "Gujarati"),
        Map.entry("ar", "Arabic"),
        Map.entry("ur", "Arabic"),
        Map.entry("ru", "Cyrillic"),
        Map.entry("uk", "Cyrillic"),
        Map.entry("bg", "Cyrillic")
        // zh / ja / ko don't have clean Latin-* paths in ICU; we rely on
        // Gemini's STT being broadly correct for those when picker is set.
    );

    private static final ConcurrentHashMap<String, Transliterator> CACHE = new ConcurrentHashMap<>();

    private TranscriptTransliterator() {}

    /**
     * Returns the transliterated text if a script swap is needed and a viable
     * ICU transliterator exists; otherwise returns the original text unchanged.
     * Safe to call on any frame — the language, script, and ICU-availability
     * checks all short-circuit, so this is a no-op for already-correct strings.
     */
    public static String maybeTransliterate(String text, String lang) {
        if (text == null || text.isBlank() || lang == null) return text;
        String targetScript = TARGET_SCRIPT.get(lang.toLowerCase());
        if (targetScript == null) return text;
        String sourceScript = detectScript(text);
        if (sourceScript == null || sourceScript.equals(targetScript)) return text;
        String icuId = sourceScript + "-" + targetScript;
        try {
            Transliterator t = CACHE.computeIfAbsent(icuId, Transliterator::getInstance);
            return t.transliterate(text);
        } catch (Exception e) {
            // Unsupported transliterator pair (e.g. Hangul-Gurmukhi). Pass through.
            return text;
        }
    }

    /**
     * Heuristic script detector based on the first non-ASCII letter we find,
     * mapped against Unicode block ranges. ASCII-only text is reported as
     * "Latin". Returning null means "unknown / unsupported" — the caller
     * passes the text through unchanged.
     */
    private static String detectScript(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) continue;
            if (!Character.isLetter(c)) continue;
            if (c >= 0x0900 && c <= 0x097F) return "Devanagari";
            if (c >= 0x0A00 && c <= 0x0A7F) return "Gurmukhi";
            if (c >= 0x0980 && c <= 0x09FF) return "Bengali";
            if (c >= 0x0A80 && c <= 0x0AFF) return "Gujarati";
            if (c >= 0x0B00 && c <= 0x0B7F) return "Oriya";
            if (c >= 0x0B80 && c <= 0x0BFF) return "Tamil";
            if (c >= 0x0C00 && c <= 0x0C7F) return "Telugu";
            if (c >= 0x0C80 && c <= 0x0CFF) return "Kannada";
            if (c >= 0x0D00 && c <= 0x0D7F) return "Malayalam";
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)) return "Arabic";
            if (c >= 0x0400 && c <= 0x04FF) return "Cyrillic";
            // Hangul / Han / Kana — no useful ICU path to/from Indic scripts,
            // bail out and leave the bubble alone.
            return null;
        }
        return "Latin";
    }
}
