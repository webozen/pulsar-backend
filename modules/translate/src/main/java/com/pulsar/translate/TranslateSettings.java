package com.pulsar.translate;

/**
 * Effective per-tenant translate configuration. Each field is the resolved
 * value: per-tenant override if set, otherwise the platform default.
 */
public record TranslateSettings(
    int sessionDurationMin,
    int extendGrantMin,
    int maxExtends,
    boolean historyEnabled,
    int historyRetentionDays
) {}
