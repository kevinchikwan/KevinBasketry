package com.basketry.catalog.cache;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The listing cache in Redis: a page is a string key with a TTL, a version is
 * an integer key without one. Compose's Redis evicts only keys with a TTL
 * (volatile-lru), so a version, like inventory-service's counters, is never
 * evicted, while pages may be (DESIGN.md Section 7). Errors and timeouts are
 * thrown as they come; {@link ProductListCacheGuard} handles them.
 */
@Component
public class RedisProductListCache implements ProductListCache {

    private final StringRedisTemplate redis;

    public RedisProductListCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long version(String scope) {
        String value = redis.opsForValue().get(CacheKeys.versionKey(scope));
        return value == null ? 0 : Long.parseLong(value);
    }

    @Override
    public void bump(String scope) {
        redis.opsForValue().increment(CacheKeys.versionKey(scope));
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void put(String key, String page, Duration ttl) {
        redis.opsForValue().set(key, page, ttl);
    }
}
