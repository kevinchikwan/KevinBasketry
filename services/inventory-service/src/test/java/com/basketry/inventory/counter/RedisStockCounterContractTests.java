package com.basketry.inventory.counter;

import com.basketry.inventory.support.TestStockCounterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Only in postgresTest: the contract against Redis and the real Lua scripts. */
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
}
