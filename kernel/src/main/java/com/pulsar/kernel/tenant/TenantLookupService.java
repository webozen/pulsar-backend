package com.pulsar.kernel.tenant;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Slug → TenantRecord lookup. A passthrough today, but wrapped in a service
 * so a cache (Redis or in-process) can be introduced later without touching
 * every caller.
 */
@Service
public class TenantLookupService {
    private final TenantRepository repo;

    public TenantLookupService(TenantRepository repo) {
        this.repo = repo;
    }

    public Optional<TenantRecord> bySlug(String slug) {
        return repo.findBySlug(slug);
    }
}
