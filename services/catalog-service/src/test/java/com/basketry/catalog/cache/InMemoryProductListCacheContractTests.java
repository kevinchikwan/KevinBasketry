package com.basketry.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.support.InMemoryProductListCache;
import com.basketry.catalog.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The contract against the fake the default run uses everywhere, and the fake's TTL. */
class InMemoryProductListCacheContractTests extends ProductListCacheContractTests {

    private final MutableClock clock = new MutableClock();
    private final InMemoryProductListCache cache = new InMemoryProductListCache(clock);

    @Override
    protected ProductListCache cache() {
        return cache;
    }

    /** Redis's own TTL is checked against a real Redis (RedisProductListCacheTests). */
    @Test
    void aPageExpiresAtItsTtl() {
        cache.put("k", "page", Duration.ofSeconds(60));

        clock.advance(Duration.ofSeconds(59));
        assertThat(cache.get("k")).isEqualTo("page");
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.get("k")).isNull();
    }
}
