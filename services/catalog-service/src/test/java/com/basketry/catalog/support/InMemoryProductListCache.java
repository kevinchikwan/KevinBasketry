package com.basketry.catalog.support;

import com.basketry.catalog.cache.ProductListCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * The same contract as the Redis cache (checked by the same contract tests,
 * ProductListCacheContractTests), held in memory, with TTLs on a clock a test
 * can move. It can fail on demand, as if Redis were down, and lets a test run
 * code at the exact moment a version is read or bumped, to place a race.
 *
 * <p>It proves nothing about Redis itself: the Redis cache and its TTLs are
 * tested against a real Redis in postgresTest.
 */
public class InMemoryProductListCache implements ProductListCache {

    private final Map<String, Long> versions = new HashMap<>();
    private final Map<String, Entry> pages = new HashMap<>();
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger bumps = new AtomicInteger();
    private Clock clock;
    private boolean unavailable;
    private Runnable onVersionRead;
    private Runnable onBump;

    public InMemoryProductListCache() {
        this(Clock.systemUTC());
    }

    public InMemoryProductListCache(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long version(String scope) {
        Runnable hook;
        synchronized (this) {
            beforeCall();
            hook = onVersionRead;
            onVersionRead = null;
        }
        // Outside the lock: the hook may bump, from this thread or another.
        if (hook != null) {
            hook.run();
        }
        synchronized (this) {
            return versions.getOrDefault(scope, 0L);
        }
    }

    @Override
    public void bump(String scope) {
        Runnable hook;
        synchronized (this) {
            beforeCall();
            hook = onBump;
        }
        if (hook != null) {
            hook.run();
        }
        synchronized (this) {
            versions.merge(scope, 1L, Long::sum);
            bumps.incrementAndGet();
        }
    }

    @Override
    public synchronized String get(String key) {
        beforeCall();
        Entry entry = pages.get(key);
        if (entry == null || !clock.instant().isBefore(entry.expiresAt())) {
            pages.remove(key);
            return null;
        }
        return entry.page();
    }

    @Override
    public synchronized void put(String key, String page, Duration ttl) {
        beforeCall();
        pages.put(key, new Entry(page, clock.instant().plus(ttl)));
        puts.incrementAndGet();
    }

    /** Every key gone, every fault and hook off: a restarted, empty Redis. */
    public synchronized void clear() {
        versions.clear();
        pages.clear();
        puts.set(0);
        bumps.set(0);
        unavailable = false;
        onVersionRead = null;
        onBump = null;
    }

    /** Every call fails at once, as with Redis down; nothing is applied. */
    public synchronized void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    /** Runs {@code hook} once, inside the next version read, before the version is looked up. */
    public synchronized void onNextVersionRead(Runnable hook) {
        this.onVersionRead = hook;
    }

    /** Runs {@code hook} inside every bump, before the version changes, until cleared. */
    public synchronized void onEveryBump(Runnable hook) {
        this.onBump = hook;
    }

    public synchronized void useClock(Clock clock) {
        this.clock = clock;
    }

    /** Pages stored so far, replaced ones included. */
    public int puts() {
        return puts.get();
    }

    public int bumps() {
        return bumps.get();
    }

    public synchronized int pagesStored() {
        Instant now = clock.instant();
        return (int) pages.values().stream().filter(entry -> now.isBefore(entry.expiresAt())).count();
    }

    private void beforeCall() {
        if (unavailable) {
            throw new RedisConnectionFailureException("fake Redis is down");
        }
    }

    private record Entry(String page, Instant expiresAt) {
    }
}
