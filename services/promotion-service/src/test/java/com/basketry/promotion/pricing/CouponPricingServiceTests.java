package com.basketry.promotion.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The refusals, their order and the expiry boundary (DESIGN.md Section 2,
 * promotion-service), without a database: the repository is a mock and the
 * clock is fixed. CouponPricingDatabaseTests prices through the real tables.
 */
class CouponPricingServiceTests {

    static final CouponCode CODE = new CouponCode("SAVE10");
    static final Discount TEN_PERCENT = new Discount(DiscountType.PERCENTAGE, new BigDecimal("10.00"));
    static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant LATER = NOW.plusSeconds(86_400);
    static final Instant EARLIER = NOW.minusSeconds(86_400);
    static final long OWNER = 5;
    static final List<MerchantSubtotal> OWNER_IN_CART = List.of(subtotal(3, "20.00"), subtotal(OWNER, "10.00"));
    static final List<MerchantSubtotal> OWNER_NOT_IN_CART = List.of(subtotal(3, "20.00"), subtotal(7, "10.00"));

    private final CouponRepository coupons = mock(CouponRepository.class);

    static MerchantSubtotal subtotal(long merchantId, String amount) {
        return new MerchantSubtotal(merchantId, new BigDecimal(amount));
    }

    static MerchantDiscount discount(long merchantId, String amount) {
        return new MerchantDiscount(merchantId, new BigDecimal(amount));
    }

    static Coupon merchantCoupon(Instant expiresAt, boolean active) {
        Coupon coupon = new Coupon(CODE, CouponScope.MERCHANT, OWNER, TEN_PERCENT, expiresAt);
        if (!active) {
            coupon.deactivate();
        }
        return coupon;
    }

    private CouponPricingService serviceAt(Instant now, Coupon coupon) {
        when(coupons.findByCode(CODE)).thenReturn(Optional.ofNullable(coupon));
        return new CouponPricingService(coupons, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void assertRefused(ThrowingCallable pricing, CouponRefusal reason) {
        assertThatThrownBy(pricing).isInstanceOfSatisfying(CouponRefusedException.class,
                refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    // (a) Each refusal on its own.

    /** Mutation: {@code findByCode(code).orElseThrow()} (a NoSuchElementException, not the refusal). */
    @Test
    void anUnknownCodeIsNotFound() {
        CouponPricingService service = serviceAt(NOW, null);

        assertRefused(() -> service.price(CODE, OWNER_IN_CART), CouponRefusal.COUPON_NOT_FOUND);
    }

    /** Mutation: the active check deleted (the coupon is priced). */
    @Test
    void aDeactivatedCouponIsInactive() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(LATER, false));

        assertRefused(() -> service.price(CODE, OWNER_IN_CART), CouponRefusal.COUPON_INACTIVE);
    }

    /** Mutation: the expiry check deleted (the coupon is priced). */
    @Test
    void aCouponPastItsExpiryIsExpired() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(EARLIER, true));

        assertRefused(() -> service.price(CODE, OWNER_IN_CART), CouponRefusal.COUPON_EXPIRED);
    }

    // (b) Which refusal wins: DESIGN's order, NOT_FOUND, INACTIVE, EXPIRED, NOT_APPLICABLE.

    /** Mutation: the expiry check moved before the active check. */
    @Test
    void inactiveWinsOverExpired() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(EARLIER, false));

        assertRefused(() -> service.price(CODE, OWNER_IN_CART), CouponRefusal.COUPON_INACTIVE);
    }

    /** Mutation: the not-applicable check moved before the active check. */
    @Test
    void inactiveWinsOverNotApplicable() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(LATER, false));

        assertRefused(() -> service.price(CODE, OWNER_NOT_IN_CART), CouponRefusal.COUPON_INACTIVE);
    }

    /** Mutation: the not-applicable check moved before the expiry check. */
    @Test
    void expiredWinsOverNotApplicable() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(EARLIER, true));

        assertRefused(() -> service.price(CODE, OWNER_NOT_IN_CART), CouponRefusal.COUPON_EXPIRED);
    }

    /** Mutation: the three checks in reverse order (not applicable, expired, inactive). */
    @Test
    void inactiveWinsOverExpiredAndNotApplicable() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(EARLIER, false));

        assertRefused(() -> service.price(CODE, OWNER_NOT_IN_CART), CouponRefusal.COUPON_INACTIVE);
    }

    // (c) The not-applicable merchant coupon.

    /**
     * A refusal, never a row of zeros. Mutation: the not-applicable throw replaced
     * by an answer of 0.00 for every merchant.
     */
    @Test
    void aMerchantCouponWhoseMerchantIsNotInTheCartIsNotApplicable() {
        CouponPricingService service = serviceAt(NOW, merchantCoupon(LATER, true));

        assertRefused(() -> service.price(CODE, OWNER_NOT_IN_CART), CouponRefusal.COUPON_NOT_APPLICABLE);
    }

    // (d) A platform coupon applies to any cart.

    /**
     * Mutation: the scope condition dropped from the not-applicable check
     * (a platform coupon has no merchant, so it is never "in the cart").
     */
    @Test
    void aPlatformCouponIsPricedForAnyCart() {
        Coupon platform = new Coupon(CODE, CouponScope.PLATFORM, null, TEN_PERCENT, LATER);
        CouponPricingService service = serviceAt(NOW, platform);

        assertThat(service.price(CODE, List.of(subtotal(9, "5.00"))))
                .containsExactly(discount(9, "0.50"));
        assertThat(service.price(CODE, List.of(subtotal(3, "20.00"), subtotal(7, "10.00"), subtotal(1, "1.05"))))
                .containsExactly(discount(3, "2.00"), discount(7, "1.00"), discount(1, "0.11"));
    }

    // (e) The expiry boundary: expired when now >= expiresAt.

    /** Mutation: {@code now.isAfter(expiresAt.minusNanos(2))} (expired a nanosecond early). */
    @Test
    void oneNanosecondBeforeTheExpiryTheCouponIsPriced() {
        CouponPricingService service = serviceAt(LATER.minusNanos(1), merchantCoupon(LATER, true));

        assertThat(service.price(CODE, OWNER_IN_CART)).containsExactly(discount(3, "0.00"), discount(OWNER, "1.00"));
    }

    /** Mutation: {@code now.isAfter(expiresAt)} (the end inclusive). */
    @Test
    void exactlyAtTheExpiryTheCouponIsExpired() {
        CouponPricingService service = serviceAt(LATER, merchantCoupon(LATER, true));

        assertRefused(() -> service.price(CODE, OWNER_IN_CART), CouponRefusal.COUPON_EXPIRED);
    }

    /** Mutation: {@code now.equals(expiresAt)} (expired only at the very instant). */
    @Test
    void oneNanosecondAfterTheExpiryTheCouponIsExpired() {
        CouponPricingService service = serviceAt(LATER.plusNanos(1), merchantCoupon(LATER, true));

        assertRefused(() -> service.price(CODE, OWNER_IN_CART), CouponRefusal.COUPON_EXPIRED);
    }

    // (h) A bad cart is the caller's error, before the lookup.

    /** Mutation: the cart checked after findByCode (an unknown code then wins with a 409). */
    @Test
    void aBadCartIsRefusedBeforeTheCodeIsLookedUp() {
        CouponPricingService service = serviceAt(NOW, null);

        assertThatThrownBy(() -> service.price(CODE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.price(CODE, List.of(subtotal(3, "1.00"), subtotal(3, "2.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(coupons, never()).findByCode(any());
    }
}
