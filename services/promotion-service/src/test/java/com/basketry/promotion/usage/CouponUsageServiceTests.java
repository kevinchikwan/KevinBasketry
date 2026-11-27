package com.basketry.promotion.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.usage.CouponUsageService.Outcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The usage count, without Kafka (DESIGN.md Section 2 and 5,
 * promotion-service): one row per counted event, the event's eventId its key.
 * Its own context (the property), so its output capture is not shared.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "basketry.test.context=coupon-usage-service")
class CouponUsageServiceTests extends PromotionServiceTestBase {

    static final Instant OCCURRED = Instant.parse("2026-11-13T10:00:00.123456Z");
    static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired
    private CouponUsageService usages;

    @Autowired
    private CouponRepository coupons;

    @MockitoSpyBean
    private CouponUsageRepository spiedRows;

    private Coupon coupon(String code, Instant expiresAt) {
        return coupons.save(new Coupon(new CouponCode(code), CouponScope.MERCHANT, 3L,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), expiresAt));
    }

    static OrderSucceeded event(UUID eventId, long orderId, String code) {
        return new OrderSucceeded(eventId, orderId, code == null ? null : new CouponCode(code), OCCURRED);
    }

    private List<java.util.Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT event_id, coupon_id, order_id, occurred_at FROM coupon_usage ORDER BY order_id");
    }

    /**
     * Mutations: the insert left out (nothing counted); the order id stored as
     * the coupon id.
     */
    @Test
    void anOrderWithACouponIsCounted() {
        Coupon coupon = coupon("SPRING10", FAR_FUTURE);
        UUID eventId = UUID.randomUUID();

        assertThat(usages.record(event(eventId, 7, "SPRING10"))).isEqualTo(Outcome.COUNTED);

        assertThat(spiedRows.findAll()).singleElement().satisfies(usage -> {
            assertThat(usage.getId()).isEqualTo(eventId);
            assertThat(usage.getCouponId()).isEqualTo(coupon.getId());
            assertThat(usage.getOrderId()).isEqualTo(7);
            assertThat(usage.getOccurredAt()).isEqualTo(OCCURRED);
        });
        assertThat(spiedRows.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    /**
     * A redelivery inserts nothing, and the insert decides it, not a lookup.
     * Mutations: CouponUsage.isNew false (save merges after a SELECT: the
     * second answer is COUNTED); the primary key's violation not recognised
     * (the second call throws).
     */
    @Test
    void aRedeliveredEventInsertsNothing() {
        coupon("SPRING10", FAR_FUTURE);
        OrderSucceeded event = event(UUID.randomUUID(), 7, "SPRING10");

        assertThat(usages.record(event)).isEqualTo(Outcome.COUNTED);
        assertThat(usages.record(event)).isEqualTo(Outcome.ALREADY_COUNTED);

        assertThat(rows()).hasSize(1);
    }

    /**
     * Two events are two orders, even with one order id: after order-service
     * loses its data (an H2 restart) it reuses order ids, with new eventIds.
     * Mutation: the order id checked before the insert.
     */
    @Test
    void everyEventIsCountedEvenForAnOrderIdSeenBefore() {
        Coupon coupon = coupon("SPRING10", FAR_FUTURE);

        usages.record(event(UUID.randomUUID(), 7, "SPRING10"));
        usages.record(event(UUID.randomUUID(), 7, "SPRING10"));
        usages.record(event(UUID.randomUUID(), 8, "SPRING10"));

        assertThat(spiedRows.countByCouponId(coupon.getId())).isEqualTo(3);
    }

    /** Most orders. Mutation: a null code looked up (a NullPointerException). */
    @Test
    void anOrderWithNoCouponWritesNothing() {
        coupon("SPRING10", FAR_FUTURE);

        assertThat(usages.record(event(UUID.randomUUID(), 7, null))).isEqualTo(Outcome.NO_COUPON);

        assertThat(rows()).isEmpty();
    }

    /**
     * A coupon is never deleted, so a code with no coupon means
     * promotion-service lost its data (an H2 restart): skipped with a WARN that
     * shows the eventId and the orderId only. Mutations: the code added to the
     * WARN; an exception instead of the skip; the WARN at INFO.
     */
    @Test
    void aCodeWithNoCouponIsSkippedWithAWarnShowingOnlyTheIds(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();

        assertThat(usages.record(event(eventId, 77, "LOSTCODE7"))).isEqualTo(Outcome.UNKNOWN_COUPON);

        assertThat(rows()).isEmpty();
        assertThat(output.getAll().lines().filter(line -> line.contains(eventId.toString())))
                .singleElement().satisfies(line -> {
                    assertThat(line).contains(" WARN ").contains("orderId 77").doesNotContain("LOSTCODE7");
                });
        assertThat(output.getAll()).doesNotContain("LOSTCODE7");
    }

    /**
     * The order succeeded with the coupon, so it counts, whatever the coupon is
     * now. Mutations: only an active coupon counted; only one not yet expired.
     */
    @Test
    void anInactiveOrExpiredCouponIsStillCounted() {
        Coupon inactive = coupon("OFF10", FAR_FUTURE);
        inactive.deactivate();
        coupons.save(inactive);
        coupon("OLD10", Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(usages.record(event(UUID.randomUUID(), 7, "OFF10"))).isEqualTo(Outcome.COUNTED);
        assertThat(usages.record(event(UUID.randomUUID(), 8, "OLD10"))).isEqualTo(Outcome.COUNTED);
    }

    /**
     * Only the primary key's violation is a repeat. Any other is a bug: an
     * exception with no cause (a database failure in the chain would be retried
     * for ever), which the listener's error handler sends to the dead-letter
     * topic. Mutation: every violation taken for a repeat.
     */
    @Test
    void aViolationOtherThanThePrimaryKeyIsNotARepeat() {
        coupon("SPRING10", FAR_FUTURE);
        doThrow(new DataIntegrityViolationException("could not execute statement [violates foreign key"
                + " constraint \"fk_coupon_usage_coupon\"]")).when(spiedRows).saveAndFlush(any());

        assertThatThrownBy(() -> usages.record(event(UUID.randomUUID(), 7, "SPRING10")))
                .isInstanceOf(CouponUsageNotRecordedException.class)
                .hasNoCause();
    }
}
