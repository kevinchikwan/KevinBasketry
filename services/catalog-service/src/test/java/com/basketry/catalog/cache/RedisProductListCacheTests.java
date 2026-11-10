package com.basketry.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.support.TestCacheConfig;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Only in postgresTest: the contract against Redis, and what the shared
 * Redis's volatile-lru policy relies on (row 9 of the Day 12 review): pages
 * have a TTL, so Redis may evict them; versions have none, so it never does.
 * The Redis container runs with Compose's flags (64 MB, volatile-lru).
 */
@Tag("redis")
@SpringBootTest
@Import(TestCacheConfig.class)
class RedisProductListCacheTests extends ProductListCacheContractTests {

    @Autowired
    private RedisProductListCache cache;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void emptyRedis() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Override
    protected ProductListCache cache() {
        return cache;
    }

    /** Mutation: store a page without its TTL (pages nobody reads any more would stay until evicted). */
    @Test
    void aPageHasItsTtl() {
        String key = CacheKeys.pageKey(CacheKeys.ALL, 0, Optional.empty(), 0, 20);
        cache.put(key, "page", Duration.ofSeconds(60));

        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    /**
     * TTL -1: never evicted under volatile-lru. Mutation: bump with an expiry
     * (an evicted or expired version would drop to 0 and could reach pages
     * stored under version 0 before).
     */
    @Test
    void aVersionKeyHasNoTtl() {
        cache.bump(CacheKeys.ALL);
        cache.bump(CacheKeys.merchantScope(3));

        assertThat(redis.getExpire(CacheKeys.versionKey(CacheKeys.ALL))).isEqualTo(-1L);
        assertThat(redis.getExpire(CacheKeys.versionKey(CacheKeys.merchantScope(3)))).isEqualTo(-1L);
    }

    @Test
    void theRedisRunsWithComposesMemoryPolicy() {
        assertThat(redis.execute((RedisCallback<Object>) connection ->
                connection.serverCommands().getConfig("maxmemory-policy")).toString()).contains("volatile-lru");
    }
}
