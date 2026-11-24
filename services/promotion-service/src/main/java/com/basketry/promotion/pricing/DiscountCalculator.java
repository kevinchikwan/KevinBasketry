package com.basketry.promotion.pricing;

import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The discount maths (DESIGN.md Section 2, promotion-service, amount rules and
 * capping and splitting): a coupon's discount for every merchant in a cart.
 * Pure: no database, no clock. Whether the coupon exists, is active or has
 * expired is the pricing service's question (ROADMAP A5), asked before this.
 *
 * <ul>
 *   <li>{@code PERCENTAGE}: per eligible merchant, {@code subtotal x value / 100},
 *       HALF_UP to 2 places. The product is exact, so there is one rounding, and
 *       with a value of at most 100 it never exceeds the subtotal.</li>
 *   <li>{@code FIXED} merchant coupon: the owner's discount is capped at its
 *       own subtotal.</li>
 *   <li>{@code FIXED} platform coupon: capped at the cart's subtotal and split
 *       in proportion to each subtotal, in whole pennies: each share is floored
 *       and the k cents left over (k is fewer than the merchants) go one each to
 *       the largest remainders. Remainders are compared exactly, as integers,
 *       and equal ones by ascending merchant id, so the order the merchants are
 *       sent in never changes an answer. A share below its merchant's subtotal
 *       is floored at least a cent below it, and a share equal to it (the cap
 *       equals the cart) has no remainder, so no share exceeds its subtotal.</li>
 * </ul>
 */
public final class DiscountCalculator {

    private static final BigDecimal NONE = new BigDecimal("0.00");

    private DiscountCalculator() {
    }

    /**
     * @param ownerId the owner of a {@link CouponScope#MERCHANT} coupon, which must be in the cart
     *                (a merchant coupon whose merchant is not is A5's COUPON_NOT_APPLICABLE, never
     *                a row of zeros); null for a {@link CouponScope#PLATFORM} one
     * @param cart    every merchant in the cart, each once, in any order
     * @return one discount per merchant, in the order given, each at scale 2, 0.00 where the
     *         coupon does not apply
     * @throws IllegalArgumentException for an empty cart, a merchant given twice, or a scope and
     *                                  owner that do not fit
     */
    public static List<MerchantDiscount> price(Discount discount, CouponScope scope, Long ownerId,
                                               List<MerchantSubtotal> cart) {
        check(discount, scope, ownerId, cart);
        BigDecimal[] discounts = switch (discount.type()) {
            case PERCENTAGE -> percentage(discount.value(), scope, ownerId, cart);
            case FIXED -> scope == CouponScope.MERCHANT
                    ? fixedMerchantCoupon(discount.value(), ownerId, cart)
                    : fixedPlatformCoupon(discount.value(), cart);
        };
        List<MerchantDiscount> answer = new ArrayList<>(cart.size());
        for (int i = 0; i < cart.size(); i++) {
            answer.add(new MerchantDiscount(cart.get(i).merchantId(), discounts[i]));
        }
        return List.copyOf(answer);
    }

    private static void check(Discount discount, CouponScope scope, Long ownerId, List<MerchantSubtotal> cart) {
        if (discount == null || scope == null) {
            throw new IllegalArgumentException("A discount and a scope are needed.");
        }
        Set<Long> merchantIds = checkCart(cart);
        if (scope == CouponScope.MERCHANT && (ownerId == null || !merchantIds.contains(ownerId))) {
            throw new IllegalArgumentException("A merchant coupon's merchant must be in the cart.");
        }
        if (scope == CouponScope.PLATFORM && ownerId != null) {
            throw new IllegalArgumentException("A platform coupon has no merchant.");
        }
    }

    /**
     * The cart's own rules, which the pricing service checks before it looks the
     * coupon up, so a bad cart is the caller's error whatever the code.
     *
     * @return the merchant ids in the cart
     * @throws IllegalArgumentException for a missing or empty cart, an empty entry, or a merchant given twice
     */
    static Set<Long> checkCart(List<MerchantSubtotal> cart) {
        if (cart == null || cart.isEmpty()) {
            throw new IllegalArgumentException("A cart has at least one merchant.");
        }
        Set<Long> merchantIds = new HashSet<>();
        for (MerchantSubtotal merchant : cart) {
            if (merchant == null) {
                throw new IllegalArgumentException("A cart has no empty entry.");
            }
            if (!merchantIds.add(merchant.merchantId())) {
                throw new IllegalArgumentException("Merchant " + merchant.merchantId() + " is in the cart twice.");
            }
        }
        return merchantIds;
    }

    private static boolean eligible(CouponScope scope, Long ownerId, MerchantSubtotal merchant) {
        return scope == CouponScope.PLATFORM || ownerId == merchant.merchantId();
    }

    private static BigDecimal[] percentage(BigDecimal value, CouponScope scope, Long ownerId,
                                           List<MerchantSubtotal> cart) {
        BigDecimal[] discounts = new BigDecimal[cart.size()];
        for (int i = 0; i < cart.size(); i++) {
            MerchantSubtotal merchant = cart.get(i);
            discounts[i] = eligible(scope, ownerId, merchant)
                    ? merchant.subtotal().multiply(value).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
                    : NONE;
        }
        return discounts;
    }

    private static BigDecimal[] fixedMerchantCoupon(BigDecimal value, Long ownerId, List<MerchantSubtotal> cart) {
        BigDecimal[] discounts = new BigDecimal[cart.size()];
        for (int i = 0; i < cart.size(); i++) {
            MerchantSubtotal merchant = cart.get(i);
            discounts[i] = eligible(CouponScope.MERCHANT, ownerId, merchant)
                    ? value.min(merchant.subtotal())
                    : NONE;
        }
        return discounts;
    }

    private static BigDecimal[] fixedPlatformCoupon(BigDecimal value, List<MerchantSubtotal> cart) {
        int size = cart.size();
        BigInteger[] subtotals = new BigInteger[size];
        BigInteger cartSubtotal = BigInteger.ZERO;
        for (int i = 0; i < size; i++) {
            subtotals[i] = pennies(cart.get(i).subtotal());
            cartSubtotal = cartSubtotal.add(subtotals[i]);
        }
        BigInteger capped = pennies(value).min(cartSubtotal);

        BigInteger[] shares = new BigInteger[size];
        BigInteger[] remainders = new BigInteger[size];
        BigInteger floors = BigInteger.ZERO;
        for (int i = 0; i < size; i++) {
            // capped x subtotal / cartSubtotal, exactly: the floor, and the remainder over cartSubtotal.
            BigInteger[] floorAndRemainder = capped.multiply(subtotals[i]).divideAndRemainder(cartSubtotal);
            shares[i] = floorAndRemainder[0];
            remainders[i] = floorAndRemainder[1];
            floors = floors.add(shares[i]);
        }

        // Fewer than size: each remainder is under one penny.
        int leftover = capped.subtract(floors).intValueExact();
        Comparator<Integer> largestRemainderFirst = Comparator
                .comparing((Integer i) -> remainders[i], Comparator.reverseOrder())
                .thenComparingLong(i -> cart.get(i).merchantId());
        List<Integer> byRemainder = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            byRemainder.add(i);
        }
        byRemainder.sort(largestRemainderFirst);
        for (int j = 0; j < leftover; j++) {
            int i = byRemainder.get(j);
            shares[i] = shares[i].add(BigInteger.ONE);
        }

        BigDecimal[] discounts = new BigDecimal[size];
        for (int i = 0; i < size; i++) {
            discounts[i] = new BigDecimal(shares[i], 2);
        }
        return discounts;
    }

    /** An amount at scale 2 (a Discount's value or a MerchantSubtotal's) in pennies. */
    private static BigInteger pennies(BigDecimal amount) {
        return amount.movePointRight(2).toBigIntegerExact();
    }
}
