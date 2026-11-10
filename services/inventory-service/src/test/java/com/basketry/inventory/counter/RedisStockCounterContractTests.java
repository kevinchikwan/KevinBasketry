package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.support.TestStockCounterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Only in postgresTest: the contract against Redis and the real Lua scripts,
 * and what the shared Redis's volatile-lru policy relies on (DESIGN.md Section
 * 7): a counter has no TTL, so Redis never evicts it.
 */
@Tag("redis")
@SpringBootTest
@Import(TestStockCounterConfig.class)
class RedisStockCounterContractTests extends StockCounterContractTests {

    @Autowired
    private RedisStockCounter counter;

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
    protected StockCounter counter() {
        return counter;
    }

    /**
     * TTL -1 for every way a counter is written, so volatile-lru never evicts
     * one (catalog-service's pages, which have a TTL, are evicted instead).
     * Mutation: load or overwrite a counter with an expiry.
     */
    @Test
    void aCounterHasNoTtlHoweverItWasWritten() {
        counter.loadIfAbsent(42, 5);
        counter.overwrite(43, 5);
        counter.take(42, 1);
        counter.addIfLoaded(43, 2);

        assertThat(redis.getExpire(StockCounter.key(42))).isEqualTo(-1L);
        assertThat(redis.getExpire(StockCounter.key(43))).isEqualTo(-1L);
    }
}