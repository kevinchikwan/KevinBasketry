package com.basketry.promotion.pricing;

import static com.basketry.promotion.coupon.CouponScope.MERCHANT;
import static com.basketry.promotion.coupon.CouponScope.PLATFORM;
import static com.basketry.promotion.coupon.DiscountType.FIXED;
import static com.basketry.promotion.coupon.DiscountType.PERCENTAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hand-made cases for the discount maths (DESIGN.md Section 2,
 * promotion-service). The answers compare BigDecimal with equals, so the
 * scale counts: 2.55 is not 2.550. DiscountCalculatorPropertyTests checks the
 * same rules on random carts.
 */
class DiscountCalculatorTests {

    /** DESIGN Section 3's example. Mutation: a platform coupon treated as eligible nowhere. */
    @Test
    void designsExampleTenPercentOnAPlatformCoupon() {
        assertThat(price(PERCENTAGE, "10.00", PLATFORM, null, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "2.55"), discount(5, "1.00"));
    }

    /** Mutation: the rate rounded to 2 places first (0.125 to 0.13: 1.30). */
    @Test
    void aPercentageIsRoundedOnceAfterMultiplying() {
        assertThat(price(PERCENTAGE, "12.50", PLATFORM, null, cart(1, "10.00")))
                .containsExactly(discount(1, "1.25"));
    }

    /** 0.10 x 5% = 0.005 exactly. Mutations: HALF_EVEN (0.00); HALF_DOWN (0.00). */
    @Test
    void anExactHalfPennyRoundsUp() {
        assertThat(price(PERCENTAGE, "5.00", PLATFORM, null, cart(1, "0.10")))
                .containsExactly(discount(1, "0.01"));
    }

    /** Mutation: divide by 100 without setScale(2) (25.5000 at scale 4). */
    @Test
    void aHundredPercentCouponTakesTheWholeSubtotalOfEveryEligibleMerchant() {
        assertThat(price(PERCENTAGE, "100.00", PLATFORM, null, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "25.50"), discount(5, "10.00"));
        assertThat(price(PERCENTAGE, "100.00", MERCHANT, 5L, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "0.00"), discount(5, "10.00"));
    }

    /** Mutation: a merchant coupon treated as a platform coupon (merchant 3 gets 2.55). */
    @Test
    void aMerchantCouponDiscountsOnlyItsOwnMerchant() {
        assertThat(price(PERCENTAGE, "10.00", MERCHANT, 5L, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "0.00"), discount(5, "1.00"));
        assertThat(price(FIXED, "4.00", MERCHANT, 5L, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "0.00"), discount(5, "4.00"));
    }

    /** Mutations: the merchant coupon's cap dropped (1000.00); the platform cap dropped (shares above the subtotals). */
    @Test
    void aCouponLargerThanTheCartIsCapped() {
        assertThat(price(FIXED, "1000.00", MERCHANT, 3L, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "25.50"), discount(5, "0.00"));
        assertThat(price(FIXED, "1000.00", PLATFORM, null, cart(3, "25.50", 5, "10.00")))
                .containsExactly(discount(3, "25.50"), discount(5, "10.00"));
    }

    /**
     * Exact shares 0.025 each, one penny left, the remainders equal: the lower
     * merchant id gets it, whichever is sent first. Mutation: equal remainders
     * broken by cart position (merchant 5 gets 0.03 when sent first).
     */
    @Test
    void aPennyTieGoesToTheLowerMerchantIdInAnyOrder() {
        assertThat(price(FIXED, "0.05", PLATFORM, null, cart(5, "10.00", 3, "10.00")))
                .containsExactly(discount(5, "0.02"), discount(3, "0.03"));
        assertThat(price(FIXED, "0.05", PLATFORM, null, cart(3, "10.00", 5, "10.00")))
                .containsExactly(discount(3, "0.03"), discount(5, "0.02"));
    }

    /** Mutation: equal remainders broken by descending merchant id (merchant 7 gets it). */
    @Test
    void aOnePennyCouponAcrossThreeEqualMerchantsGoesToTheLowestId() {
        assertThat(price(FIXED, "0.01", PLATFORM, null, cart(7, "10.00", 2, "10.00", 4, "10.00")))
                .containsExactly(discount(7, "0.00"), discount(2, "0.01"), discount(4, "0.00"));
    }

    /** Mutation: the pennies given to the smallest remainders first (merchant 1 gets it). */
    @Test
    void aOnePennyCouponAcrossThreeMerchantsGoesToTheLargestRemainder() {
        assertThat(price(FIXED, "0.01", PLATFORM, null, cart(1, "5.00", 2, "20.00", 3, "10.00")))
                .containsExactly(discount(1, "0.00"), discount(2, "0.01"), discount(3, "0.00"));
    }

    /**
     * Three equal remainders and two pennies: one each to the two lowest ids.
     * Mutation: every leftover penny given to the first merchant in the sorted order.
     */
    @Test
    void aTieLargerThanThePenniesLeftGivesOneCentEach() {
        assertThat(price(FIXED, "0.02", PLATFORM, null, cart(9, "10.00", 4, "10.00", 6, "10.00")))
                .containsExactly(discount(9, "0.00"), discount(4, "0.01"), discount(6, "0.01"));
    }

    /**
     * Exact shares 0.005, 0.005, 0.01, 0.01: floors 0, 0, 1, 1 and one penny
     * left, which goes to the tie at half a penny (merchant 3, the lower of the
     * two), never to a merchant whose share was already whole, even one with a
     * lower id. Mutation: the order by merchant id alone, remainders ignored
     * (merchant 1 gets 0.02).
     */
    @Test
    void aMerchantWhoseShareIsWholePenniesGetsNoLeftoverPenny() {
        assertThat(price(FIXED, "0.03", PLATFORM, null, cart(3, "1.00", 4, "1.00", 1, "2.00", 2, "2.00")))
                .containsExactly(discount(3, "0.01"), discount(4, "0.00"), discount(1, "0.01"), discount(2, "0.01"));
    }

    /**
     * The bound is reached: merchant 3's exact share is 0.00995 and its penny
     * makes the share equal to its subtotal (net zero, no payment leg).
     * Mutation: the last merchant takes every leftover penny (0.99, 0.99, 0.02).
     */
    @Test
    void aShareCanReachButNeverPassItsSubtotal() {
        assertThat(price(FIXED, "2.00", PLATFORM, null, cart(1, "1.00", 2, "1.00", 3, "0.01")))
                .containsExactly(discount(1, "1.00"), discount(2, "0.99"), discount(3, "0.01"));
    }

    /**
     * Pennies times pennies passes Long.MAX_VALUE here (10^12 x 10^12).
     * Mutation: the product in long arithmetic (it overflows silently).
     */
    @Test
    void theLargestAmountsAreSplitExactly() {
        assertThat(price(FIXED, "9999999999.99", PLATFORM, null,
                cart(1, "9999999999.99", 2, "9999999999.99", 3, "0.01")))
                .containsExactly(discount(1, "4999999999.99"), discount(2, "4999999999.99"), discount(3, "0.01"));
    }

    /** Mutation: the empty check dropped (an IndexOutOfBounds or empty answer instead). */
    @Test
    void anEmptyCartIsRefused() {
        assertRefused(FIXED, "1.00", PLATFORM, null, List.of());
        assertRefused(FIXED, "1.00", PLATFORM, null, null);
    }

    /** Mutation: the repeated-merchant check dropped. */
    @Test
    void aMerchantGivenTwiceIsRefused() {
        assertRefused(PERCENTAGE, "10.00", PLATFORM, null, cart(3, "1.00", 3, "2.00"));
    }

    /** Mutation: a merchant coupon whose merchant is not in the cart answered with zeros. */
    @Test
    void aMerchantCouponWhoseMerchantIsNotInTheCartIsRefusedNotZeroed() {
        assertRefused(PERCENTAGE, "10.00", MERCHANT, 9L, cart(3, "1.00", 5, "2.00"));
        assertRefused(FIXED, "1.00", MERCHANT, null, cart(3, "1.00"));
    }

    /** Mutation: the platform check dropped (merchant 3 priced as a platform coupon). */
    @Test
    void aPlatformCouponWithAMerchantIsRefused() {
        assertRefused(FIXED, "1.00", PLATFORM, 3L, cart(3, "1.00"));
    }

    /** Mutation: the discount and scope check dropped (a NullPointerException instead). */
    @Test
    void aDiscountAndAScopeAreRequired() {
        assertThatThrownBy(() -> DiscountCalculator.price(null, PLATFORM, null, cart(3, "1.00")))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountCalculator.price(new Discount(FIXED, BigDecimal.ONE), null, null,
                cart(3, "1.00"))).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Mutations: {@code signum() < 0} (0.00 accepted, then a division by zero);
     * the scale check dropped (an ArithmeticException instead); {@code scale() > 2}
     * without stripTrailingZeros (10.500 refused); {@code merchantId < 0}.
     */
    @Test
    void aSubtotalIsAboveZeroWithAtMostTwoDecimalsAndAMerchantIdAboveZero() {
        assertThatThrownBy(() -> new MerchantSubtotal(3, new BigDecimal("0.00")))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantSubtotal(3, new BigDecimal("-1.00")))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantSubtotal(3, new BigDecimal("1.005")))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantSubtotal(3, null))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantSubtotal(0, BigDecimal.ONE))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(new MerchantSubtotal(3, new BigDecimal("10.500")).subtotal()).isEqualTo(new BigDecimal("10.50"));
        assertThat(new MerchantSubtotal(3, new BigDecimal("7")).subtotal()).isEqualTo(new BigDecimal("7.00"));
    }

    private static List<MerchantDiscount> price(DiscountType type, String value, CouponScope scope, Long ownerId,
                                                List<MerchantSubtotal> cart) {
        return DiscountCalculator.price(new Discount(type, new BigDecimal(value)), scope, ownerId, cart);
    }

    private static void assertRefused(DiscountType type, String value, CouponScope scope, Long ownerId,
                                      List<MerchantSubtotal> cart) {
        assertThatThrownBy(() -> price(type, value, scope, ownerId, cart))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /** Pairs of merchant id and subtotal, in the order sent. */
    private static List<MerchantSubtotal> cart(Object... idsAndSubtotals) {
        List<MerchantSubtotal> cart = new ArrayList<>();
        for (int i = 0; i < idsAndSubtotals.length; i += 2) {
            cart.add(new MerchantSubtotal((Integer) idsAndSubtotals[i], new BigDecimal((String) idsAndSubtotals[i + 1])));
        }
        return cart;
    }

    private static MerchantDiscount discount(long merchantId, String discount) {
        return new MerchantDiscount(merchantId, new BigDecimal(discount));
    }
}
