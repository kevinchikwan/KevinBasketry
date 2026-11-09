package com.basketry.inventory.counter;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The stock counters in Redis, one string key per product holding an integer.
 * Every check-and-change is one Lua script, so it is atomic. Redis errors and
 * timeouts are thrown as they come; {@link StockCounterGuard} handles them.
 */
@Component
public class RedisStockCounter implements StockCounter {

    private static final RedisScript<Long> TAKE = script("redis/take-stock.lua");
    private static final RedisScript<Long> ADD_IF_LOADED = script("redis/add-if-loaded.lua");

    private final StringRedisTemplate redis;

    public RedisStockCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public TakeResult take(long productId, int quantity) {
        Long answer = redis.execute(TAKE, List.of(StockCounter.key(productId)), Integer.toString(quantity));
        if (answer == null) {
            throw new IllegalStateException("the take-stock script returned nothing");
        }
        return switch (answer.intValue()) {
            case 1 -> TakeResult.TAKEN;
            case 0 -> TakeResult.INSUFFICIENT;
            case -1 -> TakeResult.NOT_LOADED;
            default -> throw new IllegalStateException("the take-stock script returned " + answer);
        };
    }

    @Override
    public void addIfLoaded(long productId, long delta) {
        redis.execute(ADD_IF_LOADED, List.of(StockCounter.key(productId)), Long.toString(delta));
    }

    @Override
    public boolean loadIfAbsent(long productId, long quantity) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(StockCounter.key(productId),
                Long.toString(quantity)));
    }

    @Override
    public void overwrite(long productId, long quantity) {
        redis.opsForValue().set(StockCounter.key(productId), Long.toString(quantity));
    }

    @Override
    public OptionalLong current(long productId) {
        String value = redis.opsForValue().get(StockCounter.key(productId));
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }

    private static RedisScript<Long> script(String path) {
        return RedisScript.of(new ClassPathResource(path), Long.class);
    }
}
