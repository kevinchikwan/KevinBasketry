package com.basketry.catalog.cache;

import java.time.Duration;

/**
 * The store behind the listing cache (DESIGN.md Section 2, catalog-service):
 * pages with a TTL, and one version counter per scope with none. Implemented
 * by {@link RedisProductListCache}; tests that need no Docker use an in-memory
 * fake with the same contract.
 *
 * <p>Any method may throw when the store is unreachable or slow. Callers go
 * through {@link ProductListCacheGuard}, which turns that into "unavailable".
 */
public interface ProductListCache {

    /** The scope's version; 0 if it has never been bumped. Never creates it. */
    long version(String scope);

    /** Adds one to the scope's version, creating it at 1. The counter has no TTL. */
    void bump(String scope);

    /** The page stored under {@code key}, or null. */
    String get(String key);

    /** Stores a page under {@code key} for {@code ttl}, replacing any page there. */
    void put(String key, String page, Duration ttl);
}
