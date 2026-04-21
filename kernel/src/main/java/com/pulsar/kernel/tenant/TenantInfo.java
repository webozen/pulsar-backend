package com.pulsar.kernel.tenant;

import java.util.Set;

public record TenantInfo(
    long id,
    String slug,
    String name,
    String dbName,
    Set<String> activeModules,
    boolean suspended
) {}
