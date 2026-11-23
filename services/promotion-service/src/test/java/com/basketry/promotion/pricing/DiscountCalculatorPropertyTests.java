package com.basketry.promotion.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The discount maths on random carts (DESIGN.md Section 2, promotion-service).
 * The normal build runs {@link #FIXED_SEEDS} seeds of {@value #CARTS_PER_FIXED_SEED}
 * carts, the same carts every time. {@code -Ddiscount.seed=random} on the
 * Gradle command line runs {@value #CARTS_PER_CHOSEN_SEED} carts from a fresh
 * seed, and {@code -Ddiscount.seed=<seed>} replays one: its first carts are
 * the fixed run's carts for that seed. A failure names its seed and cart.
 *
 * <p>The generator copies another merchant's subtotal for about 3 merchants in
 * 10, so equal remainders (ties) are common, and it picks values at and around
 * the cart's subtotal, a penny, and the maximum.
 */
class DiscountCalculatorPropertyTests {

    private static final String SEED_PROPERTY = "discount.seed";
    private static final List<Long> FIXED_SEEDS = LongStream.rangeClosed(1, 20).boxed().toList();
    private static final int CARTS_PER_FIXED_SEED = 1_000;
    private static final int CARTS_PER_CHOSEN_SEED = 20_000;
    private static final long MAX_PENNIES = 999_999_999_999L; // Discount.MAX_FIXED

    private static final List<Run> RUNS = runs();

    /**
     * FIXED: a platform coupon's shares sum to min(value, cart subtotal); a
     * merchant coupon's to min(value, its merchant's subtotal). Mutation: the
     * leftover pennies not given (the floors only).
     */
    @Test
    void fixedSharesSumExactlyToTheCappedValue() {
        forEveryCart(c -> {
            if (c.discount().type() != DiscountType.FIXED) {
                return;
            }
            BigDecimal eligibleSubtotal = c.scope() == CouponScope.PLATFORM ? c.cartSubtotal()
                    : c.subtotalOf(c.ownerId());
            assertThat(sum(c.price())).isEqualTo(c.discount().value().min(eligibleSubtotal));
        });
    }

    /** Every discount is from 0.00 to its merchant's subtotal. Mutation: a platform coupon not capped at the cart. */
    @Test
    void everyDiscountIsBetweenZeroAndItsSubtotal() {
        forEveryCart(c -> {
            List<MerchantDiscount> answer = c.price();
            for (int i = 0; i < answer.size(); i++) {
                assertThat(answer.get(i).discount()).isBetween(BigDecimal.ZERO, c.cart().get(i).subtotal());
            }
        });
    }

    /** Mutation: every merchant eligible for a merchant coupon. */
    @Test
    void aMerchantCouponDiscountsOnlyItsOwnMerchant() {
        forEveryCart(c -> {
            if (c.scope() != CouponScope.MERCHANT) {
                return;
            }
            for (MerchantDiscount d : c.price()) {
                if (d.merchantId() != c.ownerId()) {
                    assertThat(d.discount()).isEqualTo(new BigDecimal("0.00"));
                }
            }
        });
    }

    /**
     * The same cart in another order gets the same discount per merchant, ties
     * included. Mutations: equal remainders broken by cart position; the
     * pennies given by cart position, remainders ignored.
     */
    @Test
    void theAnswerDoesNotDependOnTheOrderSent() {
        forEveryCart(c -> {
            List<MerchantSubtotal> shuffled = new ArrayList<>(c.cart());
            Collections.shuffle(shuffled, new Random(c.shuffleSeed()));
            List<MerchantDiscount> again = DiscountCalculator.price(c.discount(), c.scope(), c.ownerId(), shuffled);
            assertThat(byMerchant(again)).isEqualTo(byMerchant(c.price()));
        });
    }

    /**
     * Pricing a copy (new objects, equal values) gives an equal answer.
     * Mutation: equal remainders broken by identity hash code.
     */
    @Test
    void twoRunsGiveIdenticalAnswers() {
        forEveryCart(c -> {
            List<MerchantSubtotal> copy = new ArrayList<>();
            for (MerchantSubtotal m : c.cart()) {
                copy.add(new MerchantSubtotal(m.merchantId(), new BigDecimal(m.subtotal().toPlainString())));
            }
            Discount discount = new Discount(c.discount().type(), new BigDecimal(c.discount().value().toPlainString()));
            Long ownerId = c.ownerId() == null ? null : Long.valueOf(c.ownerId().longValue());
            assertThat(DiscountCalculator.price(discount, c.scope(), ownerId, copy)).isEqualTo(c.price());
        });
    }

    /**
     * PERCENTAGE: an eligible merchant's discount is subtotal x value / 100,
     * HALF_UP to the penny, here worked out in integers (pennies x hundredths of
     * a percent, plus a half, over 10,000). Mutation: HALF_EVEN.
     */
    @Test
    void aPercentageIsTheSubtotalTimesTheRateRoundedHalfUp() {
        forEveryCart(c -> {
            if (c.discount().type() != DiscountType.PERCENTAGE) {
                return;
            }
            BigInteger rate = pennies(c.discount().value()); // hundredths of a percent
            List<MerchantDiscount> answer = c.price();
            for (int i = 0; i < answer.size(); i++) {
                MerchantSubtotal m = c.cart().get(i);
                if (c.scope() == CouponScope.PLATFORM || m.merchantId() == c.ownerId()) {
                    BigInteger expected = pennies(m.subtotal()).multiply(rate).add(BigInteger.valueOf(5_000))
                            .divide(BigInteger.valueOf(10_000));
                    assertThat(answer.get(i).discount()).isEqualTo(new BigDecimal(expected, 2));
                }
            }
        });
    }

    /** One answer per merchant, in the order sent, at scale 2. Mutations: 0.00 as BigDecimal.ZERO; the answer sorted by merchant id. */
    @Test
    void everyAnswerIsAtScaleTwoInTheOrderSent() {
        forEveryCart(c -> {
            List<MerchantDiscount> answer = c.price();
            assertThat(answer).extracting(MerchantDiscount::merchantId)
                    .containsExactlyElementsOf(c.cart().stream().map(MerchantSubtotal::merchantId).toList());
            assertThat(answer).allSatisfy(d -> assertThat(d.discount().scale()).isEqualTo(2));
        });
    }

    /**
     * FIXED platform coupon, against exact integers: each share is its floored
     * exact share or one penny more, and the merchants given a penny come before
     * the others when sorted by remainder (largest first) and then merchant id.
     * Mutations: the last merchant takes every leftover penny; the pennies go to
     * the smallest remainders.
     */
    @Test
    void eachPlatformShareIsItsFloorOrOnePennyMoreForTheLargestRemainders() {
        forEveryCart(c -> {
            if (c.discount().type() != DiscountType.FIXED || c.scope() != CouponScope.PLATFORM) {
                return;
            }
            BigInteger cart = pennies(c.cartSubtotal());
            BigInteger capped = pennies(c.discount().value()).min(cart);
            List<MerchantDiscount> answer = c.price();
            List<Remainder> given = new ArrayList<>();
            List<Remainder> notGiven = new ArrayList<>();
            for (int i = 0; i < answer.size(); i++) {
                BigInteger[] floorAndRemainder = capped.multiply(pennies(c.cart().get(i).subtotal()))
                        .divideAndRemainder(cart);
                BigInteger extra = pennies(answer.get(i).discount()).subtract(floorAndRemainder[0]);
                assertThat(extra).isBetween(BigInteger.ZERO, BigInteger.ONE);
                Remainder remainder = new Remainder(answer.get(i).merchantId(), floorAndRemainder[1]);
                (extra.signum() > 0 ? given : notGiven).add(remainder);
            }
            for (Remainder g : given) {
                for (Remainder n : notGiven) {
                    assertThat(g.comesBefore(n)).as("merchant %d given a penny before merchant %d", g.merchantId(),
                            n.merchantId()).isTrue();
                }
            }
        });
    }

    /** A merchant's remainder over the cart subtotal, in pennies; the largest first, then the lower id. */
    private record Remainder(long merchantId, BigInteger remainder) {

        boolean comesBefore(Remainder other) {
            int byRemainder = remainder.compareTo(other.remainder);
            return byRemainder > 0 || byRemainder == 0 && merchantId < other.merchantId;
        }
    }

    private interface CartCheck {
        void check(Case c);
    }

    private record Run(long seed, int carts) {
    }

    private static List<Run> runs() {
        String chosen = System.getProperty(SEED_PROPERTY);
        if (chosen == null || chosen.isBlank()) {
            return FIXED_SEEDS.stream().map(seed -> new Run(seed, CARTS_PER_FIXED_SEED)).toList();
        }
        long seed = "random".equals(chosen.strip()) ? new SecureRandom().nextLong() : Long.parseLong(chosen.strip());
        System.out.println(SEED_PROPERTY + "=" + seed + " (" + CARTS_PER_CHOSEN_SEED + " carts)");
        return List.of(new Run(seed, CARTS_PER_CHOSEN_SEED));
    }

    private static void forEveryCart(CartCheck check) {
        for (Run run : RUNS) {
            Random random = new Random(run.seed());
            for (int i = 0; i < run.carts(); i++) {
                Case c = Case.random(random);
                try {
                    check.check(c);
                } catch (AssertionError | RuntimeException e) {
                    throw new AssertionError("seed " + run.seed() + ", cart " + i + " (replay with -D" + SEED_PROPERTY
                            + "=" + run.seed() + "; seeds this run: " + RUNS.stream().map(Run::seed).toList()
                            + "): " + c + "\n" + e.getMessage(), e);
                }
            }
        }
    }

    /** One random coupon and cart. */
    private record Case(Discount discount, CouponScope scope, Long ownerId, List<MerchantSubtotal> cart,
                        long shuffleSeed) {

        static Case random(Random r) {
            int size = 1 + r.nextInt(10);
            Set<Long> ids = new LinkedHashSet<>();
            while (ids.size() < size) {
                ids.add(r.nextInt(10) == 0 ? r.nextLong(1, Long.MAX_VALUE) : 1 + r.nextInt(60));
            }
            List<MerchantSubtotal> cart = new ArrayList<>();
            long cartPennies = 0;
            for (long id : ids) {
                long pennies;
                if (!cart.isEmpty() && r.nextInt(10) < 3) {
                    pennies = pennies(cart.get(r.nextInt(cart.size())).subtotal()).longValueExact();
                } else {
                    pennies = switch (r.nextInt(10)) {
                        case 0 -> 1 + r.nextInt(100);
                        case 1 -> r.nextLong(1, MAX_PENNIES + 1);
                        default -> 1 + r.nextInt(100_000);
                    };
                }
                cart.add(new MerchantSubtotal(id, BigDecimal.valueOf(pennies, 2)));
                cartPennies += pennies;
            }
            DiscountType type = r.nextBoolean() ? DiscountType.PERCENTAGE : DiscountType.FIXED;
            CouponScope scope = r.nextBoolean() ? CouponScope.PLATFORM : CouponScope.MERCHANT;
            Long ownerId = scope == CouponScope.MERCHANT ? cart.get(r.nextInt(size)).merchantId() : null;
            long value = type == DiscountType.PERCENTAGE
                    ? (r.nextInt(20) == 0 ? 10_000 : 1 + r.nextInt(10_000))
                    : switch (r.nextInt(8)) {
                        case 0 -> 1;
                        case 1 -> MAX_PENNIES;
                        case 2 -> cartPennies - 1;
                        case 3 -> cartPennies;
                        case 4 -> cartPennies + 1;
                        case 5 -> 1 + r.nextInt(3 * size);
                        default -> r.nextLong(1, 2 * cartPennies + 1);
                    };
            value = Math.max(1, Math.min(value, MAX_PENNIES));
            return new Case(new Discount(type, BigDecimal.valueOf(value, 2)), scope, ownerId, List.copyOf(cart),
                    r.nextLong());
        }

        List<MerchantDiscount> price() {
            return DiscountCalculator.price(discount, scope, ownerId, cart);
        }

        BigDecimal cartSubtotal() {
            return cart.stream().map(MerchantSubtotal::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal subtotalOf(long merchantId) {
            return cart.stream().filter(m -> m.merchantId() == merchantId).findFirst().orElseThrow().subtotal();
        }
    }

    private static BigDecimal sum(List<MerchantDiscount> answer) {
        return answer.stream().map(MerchantDiscount::discount).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private static Map<Long, BigDecimal> byMerchant(List<MerchantDiscount> answer) {
        Map<Long, BigDecimal> byMerchant = new HashMap<>();
        answer.forEach(d -> byMerchant.put(d.merchantId(), d.discount()));
        return byMerchant;
    }

    private static BigInteger pennies(BigDecimal amount) {
        return amount.movePointRight(2).toBigIntegerExact();
    }
}
