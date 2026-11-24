package com.basketry.promotion.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.MutableClock;
import com.basketry.promotion.support.PromotionServiceTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Coupons saved through JPA and priced by the real service bean, on H2 and (in
 * postgresTest) on PostgreSQL, with the clock set by the test.
 */
class CouponPricingDatabaseTests extends PromotionServiceTestBase {

    static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    // Microseconds, what both databases keep (ExpiryStorageTests).
    static final Instant EXPIRY = Instant.parse("2027-03-01T12:34:56.123456Z");
    // Far from any id the sequence hands out in a test run.
    static final long OWNER = 900_001;

    @TestConfiguration
    static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private CouponRepository coupons;

    @Autowired
    private CouponPricingService pricing;

    @BeforeEach
    void startAtNow() {
        clock.set(NOW);
    }

    private Coupon save(CouponCode code, CouponScope scope, Long merchantId, Discount discount) {
        return coupons.save(new Coupon(code, scope, merchantId, discount, EXPIRY));
    }

    private static MerchantSubtotal subtotal(long merchantId, String amount) {
        return new MerchantSubtotal(merchantId, new BigDecimal(amount));
    }

    private static MerchantDiscount discount(long merchantId, String amount) {
        return new MerchantDiscount(merchantId, new BigDecimal(amount));
    }

    private void assertRefused(CouponCode code, List<MerchantSubtotal> cart, CouponRefusal reason) {
        assertThatThrownBy(() -> pricing.price(code, cart)).isInstanceOfSatisfying(CouponRefusedException.class,
                refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    // (f) End to end through the database.

    /**
     * A FIXED merchant coupon, capped at its merchant's subtotal; the other
     * merchant gets 0.00. Mutation: the service passes {@code coupon.getId()} as
     * the owner instead of {@code coupon.getMerchantId()}.
     */
    @Test
    void aSavedMerchantCouponIsPricedForItsOwnMerchant() {
        save(new CouponCode("M-FIFTEEN"), CouponScope.MERCHANT, OWNER,
                new Discount(DiscountType.FIXED, new BigDecimal("15.00")));

        assertThat(pricing.price(new CouponCode("M-FIFTEEN"), List.of(subtotal(3, "20.00"), subtotal(OWNER, "10.00"))))
                .containsExactly(discount(3, "0.00"), discount(OWNER, "10.00"));
    }

    /**
     * The boundary on the stored expiry, a microsecond either side. Mutation:
     * {@code now.isAfter(expiresAt)} (the end inclusive).
     */
    @Test
    void aSavedCouponExpiresExactlyAtItsStoredExpiry() {
        CouponCode code = new CouponCode("SAVE10");
        save(code, CouponScope.PLATFORM, null, new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")));
        List<MerchantSubtotal> cart = List.of(subtotal(3, "20.00"));

        clock.set(EXPIRY.minusNanos(1_000));
        assertThat(pricing.price(code, cart)).containsExactly(discount(3, "2.00"));
        clock.set(EXPIRY);
        assertRefused(code, cart, CouponRefusal.COUPON_EXPIRED);
        clock.set(EXPIRY.plusNanos(1_000));
        assertRefused(code, cart, CouponRefusal.COUPON_EXPIRED);
    }

    // (g) Pricing writes nothing.

    /**
     * Every column of every coupon, and the row count, the same after a priced
     * coupon and after each refusal. (No coupon_usage row: that table comes
     * with ROADMAP A8, which adds the assertion here.) Mutations, each before
     * the service's return: {@code coupons.saveAndFlush(new Coupon(...))} (an
     * insert); {@code coupon.deactivate()} with {@code @Transactional(readOnly = true)}
     * made {@code @Transactional} (an update at commit). With readOnly kept, the deactivation is never
     * written even by {@code saveAndFlush}: the session is read-only, so the
     * loaded coupon is not dirty-checked.
     */
    @Test
    void pricingWritesNothing() {
        save(new CouponCode("PLATFORM10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")));
        save(new CouponCode("MERCHANT10"), CouponScope.MERCHANT, OWNER,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")));
        Coupon inactive = save(new CouponCode("OFF10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")));
        inactive.deactivate();
        coupons.save(inactive);
        List<MerchantSubtotal> cart = List.of(subtotal(3, "20.00"));
        List<Map<String, Object>> before = rows();

        assertThat(pricing.price(new CouponCode("PLATFORM10"), cart)).hasSize(1);
        assertRefused(new CouponCode("NOSUCHCODE"), cart, CouponRefusal.COUPON_NOT_FOUND);
        assertRefused(new CouponCode("OFF10"), cart, CouponRefusal.COUPON_INACTIVE);
        assertRefused(new CouponCode("MERCHANT10"), cart, CouponRefusal.COUPON_NOT_APPLICABLE);
        clock.set(EXPIRY);
        assertRefused(new CouponCode("PLATFORM10"), cart, CouponRefusal.COUPON_EXPIRED);

        assertThat(rows()).hasSize(3).isEqualTo(before);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM coupon ORDER BY id");
    }
}
