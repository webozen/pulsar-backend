package com.pulsar.kernel.tenant;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TenantLookupService {
    private static final String CACHE_PREFIX = "pulsar:tenant:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final TenantRepository repo;
    private final StringRedisTemplate redis;

    public TenantLookupService(TenantRepository repo, StringRedisTemplate redis) {
        this.repo = repo;
        this.redis = redis;
    }

    public Optional<TenantRecord> bySlug(String slug) {
        return repo.findBySlug(slug);
    }

    public void invalidate(String slug) {
        if (slug != null) redis.delete(CACHE_PREFIX + slug);
    }

    public void primeCacheExistsMarker(String slug) {
        redis.opsForValue().set(CACHE_PREFIX + slug, "1", TTL);
    }
}
