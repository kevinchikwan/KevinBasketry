package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.support.TestStockCounterConfig;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Only in postgresTest: proves the run really is on Redis, the pinned version
 * with no persistence, and that the application's counter is the Redis one,
 * so the rest of the suite passing there means something.
 */
@Tag("redis")
@SpringBootTest
@Import(TestStockCounterConfig.class)
class RedisSmokeTests {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private List<StockCounter> counters;

    @Test
    void theCounterIsRedis8_10_3WithoutPersistence() {
        Properties server = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("server"));
        Properties appendOnly = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().getConfig("appendonly"));
        Properties save = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().getConfig("save"));

        assertThat(server.getProperty("redis_version")).isEqualTo("8.10.3");
        assertThat(appendOnly.getProperty("appendonly")).isEqualTo("no");
        assertThat(save.getProperty("save")).isEmpty();
        assertThat(counters).singleElement().isInstanceOf(RedisStockCounter.class);
    }
}
