package com.basketry.inventory.support;

import com.basketry.inventory.counter.StockCounter;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * The same contract as the Redis counter (checked by the same contract tests,
 * StockCounterContractTests), held in memory: every method is synchronized, so
 * each is atomic as a Lua script is. It can also fail on demand: as if Redis
 * were down, or as if a command ran but its reply was lost.
 *
 * <p>It proves nothing about Redis itself: the Lua scripts and their atomicity
 * are tested against a real Redis in postgresTest.
 */
public class InMemoryStockCounter implements StockCounter {

    private final Map<Long, Long> counters = new HashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private boolean unavailable;
    private boolean loseNextReply;

    @Override
    public synchronized TakeResult take(long productId, int quantity) {
        beforeCall();
        Long current = counters.get(productId);
        TakeResult result;
        if (current == null) {
            result = TakeResult.NOT_LOADED;
        } else if (current >= quantity) {
            counters.put(productId, current - quantity);
            result = TakeResult.TAKEN;
        } else {
            result = TakeResult.INSUFFICIENT;
        }
        afterCall();
        return result;
    }

    @Override
    public synchronized void addIfLoaded(long productId, long delta) {
        beforeCall();
        counters.computeIfPresent(productId, (id, current) -> current + delta);
        afterCall();
    }

    @Override
    public synchronized boolean loadIfAbsent(long productId, long quantity) {
        beforeCall();
        boolean loaded = counters.putIfAbsent(productId, quantity) == null;
        afterCall();
        return loaded;
    }

    @Override
    public synchronized void overwrite(long productId, long quantity) {
        beforeCall();
        counters.put(productId, quantity);
        afterCall();
    }

    @Override
    public synchronized OptionalLong current(long productId) {
        Long value = counters.get(productId);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** Every counter gone, and every fault off: a restarted, empty Redis. */
    public synchronized void clear() {
        counters.clear();
        unavailable = false;
        loseNextReply = false;
        calls.set(0);
    }

    /** Every call fails at once, as with Redis down; nothing is applied. */
    public synchronized void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    /** The next call is applied, then fails as if its reply had been lost. */
    public synchronized void loseNextReply() {
        this.loseNextReply = true;
    }

    /** Calls that reached the counter, failed ones included; {@link #current} does not count. */
    public int calls() {
        return calls.get();
    }

    private void beforeCall() {
        calls.incrementAndGet();
        if (unavailable) {
            throw new RedisConnectionFailureException("fake Redis is down");
        }
    }

    private void afterCall() {
        if (loseNextReply) {
            loseNextReply = false;
            throw new QueryTimeoutException("fake Redis lost the reply");
        }
    }
}
