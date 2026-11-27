package com.basketry.promotion.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.promotion.support.PromotionServiceTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The constraints of V2 with plain SQL, past the application, on H2 here and
 * on PostgreSQL in postgresTest. Each refusal names its constraint, so a row
 * refused for some other reason cannot pass for it. CouponUsageService tells a
 * repeat by the primary key's name, so that name is checked on both databases.
 */
class CouponUsageTableTests extends PromotionServiceTestBase {

    private long couponId;

    @BeforeEach
    void aCoupon() {
        jdbc.update("INSERT INTO coupon (id, code, scope, merchant_id, discount_type, discount_value, expires_at, active)"
                + " VALUES (nextval('coupon_id_seq'), 'SPRING10', 'PLATFORM', NULL, 'FIXED', 5.00,"
                + " TIMESTAMP WITH TIME ZONE '2099-01-01 00:00:00+00', TRUE)");
        couponId = jdbc.queryForObject("SELECT id FROM coupon WHERE code = 'SPRING10'", Long.class);
    }

    private void insert(UUID eventId, long coupon, long orderId) {
        jdbc.update("INSERT INTO coupon_usage (event_id, coupon_id, order_id, occurred_at)"
                + " VALUES (?, ?, ?, TIMESTAMP WITH TIME ZONE '2026-11-13 10:00:00+00')", eventId, coupon, orderId);
    }

    /** Mutation: pk_coupon_usage dropped (or renamed: the service would no longer recognise a repeat). */
    @Test
    void anEventIsStoredOnce() {
        UUID eventId = UUID.randomUUID();
        insert(eventId, couponId, 7);

        assertThatThrownBy(() -> insert(eventId, couponId, 8))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CouponUsageService.PRIMARY_KEY);
    }

    /** Mutation: a unique order_id (orders after an order-service data loss reuse ids). */
    @Test
    void oneOrderIdMayHaveSeveralEvents() {
        insert(UUID.randomUUID(), couponId, 7);
        insert(UUID.randomUUID(), couponId, 7);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_usage", Long.class)).isEqualTo(2);
    }

    /** Mutations: fk_coupon_usage_coupon dropped; ck_coupon_usage_order_id dropped. */
    @Test
    void aUsageHasItsCouponAndAPositiveOrderId() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), couponId + 1000, 7))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_coupon_usage_coupon");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), couponId, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_coupon_usage_order_id");
    }
}
