package com.basketry.catalog.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The only way the service reaches the listing cache, copied from
 * inventory-service's StockCounterGuard. Redis is never needed: any failure or
 * timeout becomes "unavailable", and the caller reads the database instead.
 * After a failure, every call skips Redis for {@code skip-after-failure}
 * instead of waiting for a timeout each time.
 *
 * <p>A failed bump leaves the scope's pages reachable until their TTL ends:
 * the one way a listing can be stale, and for at most the TTL (DESIGN.md
 * Section 2, catalog-service).
 */
@Component
public class ProductListCacheGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductListCacheGuard.class);

    private final ProductListCache cache;
    private final Duration ttl;
    private final long skipMillis;
    private final Clock clock;

    private volatile long skipUntil;
    private volatile boolean down;

    @Autowired
    public ProductListCacheGuard(ProductListCache cache, CacheProperties properties) {
        this(cache, properties, Clock.systemUTC());
    }

    public ProductListCacheGuard(ProductListCache cache, CacheProperties properties, Clock clock) {
        this.cache = cache;
        this.ttl = properties.ttl();
        this.skipMillis = properties.skipAfterFailure().toMillis();
        this.clock = clock;
    }

    /** Empty if Redis was skipped or failed. */
    public OptionalLong version(String scope) {
        Long version = call(() -> cache.version(scope));
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }

    /** False if Redis was skipped or failed. */
    public boolean bump(String scope) {
        return call(() -> {
            cache.bump(scope);
            return Boolean.TRUE;
        }) != null;
    }

    /** Empty on a miss, or if Redis was skipped or failed. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(call(() -> cache.get(key)));
    }

    /** False if Redis was skipped or failed. */
    public boolean put(String key, String page) {
        return call(() -> {
            cache.put(key, page, ttl);
            return Boolean.TRUE;
        }) != null;
    }

    /** Null if skipped or failed. */
    private <T> T call(Supplier<T> action) {
        if (clock.millis() < skipUntil) {
            return null;
        }
        try {
            T result = action.get();
            if (down) {
                down = false;
                log.info("The listing cache (Redis) is reachable again");
            }
            return result;
        } catch (RuntimeException e) {
            skipUntil = clock.millis() + skipMillis;
            if (!down) {
                down = true;
                log.warn("The listing cache (Redis) is unavailable ({}: {}). Listings are read from the database;"
                        + " Redis is tried again in {} ms", e.getClass().getSimpleName(), e.getMessage(), skipMillis);
            } else {
                log.debug("The listing cache (Redis) is still unavailable", e);
            }
            return null;
        }
    }
}
