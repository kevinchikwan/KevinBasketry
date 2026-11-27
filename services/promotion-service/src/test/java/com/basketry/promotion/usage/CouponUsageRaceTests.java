package com.basketry.promotion.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.usage.CouponUsageService.Outcome;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One event counted twice at the same time, on PostgreSQL (a redelivery during
 * a rebalance): one row, and neither call fails. The primary key decides, not
 * a lookup before the insert, which both calls could pass.
 */
@Tag("postgres")
class CouponUsageRaceTests extends PromotionServiceTestBase {

    @Autowired
    private CouponUsageService usages;

    @Autowired
    private CouponRepository coupons;

    @Autowired
    private DataSource dataSource;

    private long couponId;

    @BeforeEach
    void aCoupon() {
        Coupon coupon = coupons.save(new Coupon(new CouponCode("RACE10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.FIXED, new BigDecimal("5.00")), CouponUsageServiceTests.FAR_FUTURE));
        couponId = coupon.getId();
    }

    /**
     * Made certain: another transaction has inserted the event and not yet
     * committed, so the call's insert waits on the primary key; the other
     * commits, and the call answers ALREADY_COUNTED. Mutation: the primary
     * key's violation not recognised (the call throws).
     */
    @Test
    void aCallThatLosesToAnUncommittedInsertIsAlreadyCounted() throws Exception {
        UUID eventId = UUID.randomUUID();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement insert = other.prepareStatement("INSERT INTO coupon_usage (event_id, coupon_id,"
                    + " order_id, occurred_at) VALUES (?, ?, 7, ?)")) {
                insert.setObject(1, eventId);
                insert.setLong(2, couponId);
                insert.setTimestamp(3, Timestamp.from(CouponUsageServiceTests.OCCURRED));
                insert.executeUpdate();
            }
            CompletableFuture<Outcome> call = CompletableFuture.supplyAsync(() ->
                    usages.record(CouponUsageServiceTests.event(eventId, 7, "RACE10")));
            waitForALockWait();
            other.commit();

            assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.ALREADY_COUNTED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_usage", Long.class)).isOne();
    }

    /**
     * Pairs of calls with one event, 20 times: each pair is one COUNTED and one
     * ALREADY_COUNTED. Mutation: the primary key's violation not recognised.
     */
    @Test
    void twoCallsWithOneEventAreCountedOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            OrderSucceeded event = CouponUsageServiceTests.event(UUID.randomUUID(), 100 + round, "RACE10");
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Outcome>> pair = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                pair.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return usages.record(event);
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (CompletableFuture<Outcome> future : pair) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).as("round %d", round)
                    .containsExactlyInAnyOrder(Outcome.COUNTED, Outcome.ALREADY_COUNTED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_usage", Long.class)).isEqualTo(20);
    }

    /** Until a statement of this database waits on a lock (the call's insert), at most 10 seconds. */
    private void waitForALockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The call never waited on the uncommitted insert");
    }
}
