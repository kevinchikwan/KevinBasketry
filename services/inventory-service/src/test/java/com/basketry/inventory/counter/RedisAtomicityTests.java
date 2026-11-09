package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.counter.StockCounter.TakeResult;
import com.basketry.inventory.support.TestStockCounterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Only in postgresTest: the Redis guard alone, with PostgreSQL out of the
 * picture. 16 threads take from a counter holding 1, a hundred times over;
 * each time exactly one gets it and the counter ends at 0, never below.
 */
@Tag("redis")
@SpringBootTest
@Import(TestStockCounterConfig.class)
class RedisAtomicityTests {

    private static final int THREADS = 16;
    private static final int ROUNDS = 100;

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

    @Test
    void sixteenThreadsTakingTheLastUnitGetItExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long productId = 1_000 + round;
                counter.overwrite(productId, 1);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<TakeResult>> results = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return counter.take(productId, 1);
                    }));
                }
                start.countDown();
                List<TakeResult> outcomes = new ArrayList<>();
                for (Future<TakeResult> result : results) {
                    outcomes.add(result.get(30, TimeUnit.SECONDS));
                }

                assertThat(outcomes).as("round %d", round).filteredOn(TakeResult.TAKEN::equals).hasSize(1);
                assertThat(counter.current(productId)).as("round %d", round).hasValue(0);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
