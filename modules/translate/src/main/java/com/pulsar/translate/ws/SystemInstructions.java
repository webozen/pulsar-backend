package com.pulsar.translate.ws;

import java.util.Map;

public class SystemInstructions {

    private static final Map<String, String> LANG_NAMES = Map.ofEntries(
        Map.entry("en", "English"), Map.entry("hi", "Hindi"), Map.entry("es", "Spanish"),
        Map.entry("fr", "French"), Map.entry("zh", "Mandarin Chinese"), Map.entry("ar", "Arabic"),
        Map.entry("pt", "Portuguese"), Map.entry("de", "German"), Map.entry("ja", "Japanese"),
        Map.entry("ko", "Korean"), Map.entry("it", "Italian"), Map.entry("ru", "Russian"),
        Map.entry("vi", "Vietnamese"), Map.entry("ta", "Tamil"), Map.entry("pa", "Punjabi"),
        Map.entry("ur", "Urdu"), Map.entry("gu", "Gujarati"), Map.entry("bn", "Bengali"),
        Map.entry("te", "Telugu"), Map.entry("mr", "Marathi"), Map.entry("kn", "Kannada"),
        Map.entry("ml", "Malayalam")
    );

    public static final java.util.Set<String> VALID_MODES = java.util.Set.of(
        "live-translate", "conversation", "transcribe-extract", "voice-translate"
    );

    public static String build(String mode, String sourceLang, String targetLang) {
        boolean isAuto = "auto".equals(sourceLang);
        String srcName = isAuto ? null : LANG_NAMES.getOrDefault(sourceLang, sourceLang);
        String tgtName = LANG_NAMES.getOrDefault(targetLang, targetLang);
        String sourceDesc = isAuto ? "the spoken language (auto-detect it)" : "spoken " + srcName;

        String detectClause = isAuto
            ? "- Automatically detect the language being spoken. The speaker may mix multiple languages — this is normal.\n"
              + "- After your first translation, indicate the detected language in brackets like [Hindi] once, then stop.\n"
            : "";

        return switch (mode) {
            case "live-translate" -> String.format("""
                You are a real-time interpreter. Your ONLY job is to translate %s into %s.

                RULES:
                %s- Output ONLY the translated text. No explanations, no commentary.
                - Translate naturally and idiomatically.
                - Preserve the speaker's intent, tone, and meaning.
                - If audio is unclear, translate what you can hear.
                - Never add text the speaker did not say.
                - Respond as quickly as possible.""", sourceDesc, tgtName, detectClause);

            case "transcribe-extract" -> String.format("""
                You are a real-time transcriber and conversation analyst.
                %s
                PHASE 1 (during conversation): Transcribe the audio. %s
                PHASE 2 (when user says "summarize"): Provide a structured summary in %s with key topics and action items.""",
                detectClause,
                isAuto ? "Auto-detect the language." : "Transcribe in " + srcName + ".",
                tgtName);

            case "conversation" -> buildConversation(tgtName, targetLang);

            case "voice-translate" -> String.format("""
                You are a real-time voice interpreter. Translate %s into spoken %s.
                %s- Translate naturally. Respond as quickly as possible. Never add content the speaker did not say.""",
                sourceDesc, tgtName, detectClause);

            default -> "You are a helpful translation assistant.";
        };
    }

    private static String buildConversation(String tgtName, String targetLang) {
        String otherLang = "en".equals(targetLang)
            ? "the non-English language spoken by the other party (auto-detect it)"
            : tgtName;

        String bootstrap = "en".equals(targetLang) ? """

LANGUAGE BOOTSTRAP:
- If English is spoken before the other party says anything, output NOTHING.
- Once the other party speaks in a non-English language, translate their words to English and remember their language.
- From that point, translate normally in both directions.
""" : "";

        return String.format("""
            You are a real-time bidirectional interpreter. One person speaks English. The other speaks %s.
            %s
            RULES:
            - Detect which language is spoken on each turn and translate to the OTHER language.
            - Output ONLY the translated text. No labels, no commentary.
            - Translate naturally and idiomatically.
            - This is an office setting — translate domain-specific terms accurately.
            - If the speaker mixes languages, understand the combined meaning and translate.
            - Never add text the speaker did not say. Respond as quickly as possible.
            - When translating TO a non-English language, ALWAYS use native script (Devanagari for Hindi, etc.).""",
            otherLang, bootstrap);
    }
}
