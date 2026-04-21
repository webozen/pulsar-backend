package com.pulsar.kernel.module;

public record ModuleManifest(
    String id,
    String title,
    String tagline,
    String icon,
    String category
) {}
