package com.basketry.order.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.order.order.Amounts.MerchantAmounts;
import com.basketry.order.order.Amounts.PricedLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The money of a checkout (DESIGN.md Section 2, order-service, amounts and commission). */
class AmountsTests {

    private static final Map<Long, BigDecimal> FIVE_PERCENT = Map.of(3L, new BigDecimal("5.00"),
            4L, new BigDecimal("5.00"));

    private static PricedLine line(long productId, long merchantId, String price, int quantity) {
        return new PricedLine(productId, merchantId, "Product " + productId, new BigDecimal(price), quantity);
    }

    /** Mutation: the quantity left out of the line amount. */
    @Test
    void aLineAmountIsThePriceTimesTheQuantityExactly() {
        Amounts amounts = Amounts.of(List.of(line(42, 3, "99999.99", 1000)), FIVE_PERCENT, Map.of());

        assertThat(amounts.lines().getFirst().lineAmount()).isEqualTo(new BigDecimal("99999990.00"));
    }

    /**
     * HALF_UP on the amount, 2 decimal places: 2.50 at 5.00% is 0.125, so 0.13
     * (HALF_EVEN, HALF_DOWN and DOWN give 0.12); 2.42 at 5.00% is 0.121, so 0.12
     * (UP and CEILING give 0.13). Mutations: RoundingMode.HALF_EVEN; RoundingMode.UP.
     */
    @Test
    void theCommissionIsRoundedHalfUpToTwoDecimals() {
        assertThat(Amounts.commission(new BigDecimal("2.50"), new BigDecimal("5.00")))
                .isEqualTo(new BigDecimal("0.13"));
        assertThat(Amounts.commission(new BigDecimal("2.42"), new BigDecimal("5.00")))
                .isEqualTo(new BigDecimal("0.12"));
        assertThat(Amounts.commission(new BigDecimal("100.00"), new BigDecimal("0.00")))
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(Amounts.commission(new BigDecimal("100.00"), new BigDecimal("100.00")))
                .isEqualTo(new BigDecimal("100.00"));
    }

    /**
     * 2,000 random carts of 1 to 100 lines over 1 to 6 merchants: one charge per
     * merchant in order of first appearance, each merchant's subtotal is the sum
     * of its lines, the charges sum to the total, and the total is the sum of
     * every line. Mutations: a merchant's subtotal keeps only its first line
     * ({@code putIfAbsent} for {@code merge}); the total leaves out the last
     * merchant.
     */
    @Test
    void theChargesSumToTheTotalAndTheTotalIsEveryLine() {
        Random random = new Random(20261113);
        for (int cart = 0; cart < 2000; cart++) {
            int merchantCount = 1 + random.nextInt(6);
            Map<Long, BigDecimal> rates = new HashMap<>();
            for (long merchant = 1; merchant <= merchantCount; merchant++) {
                rates.put(merchant, BigDecimal.valueOf(random.nextInt(10001), 2));
            }
            List<PricedLine> lines = new ArrayList<>();
            int lineCount = 1 + random.nextInt(100);
            for (long product = 1; product <= lineCount; product++) {
                lines.add(new PricedLine(product, 1 + random.nextInt(merchantCount), "P",
                        BigDecimal.valueOf(1 + random.nextInt(9_999_999), 2), 1 + random.nextInt(1000)));
            }

            Amounts amounts = Amounts.of(lines, rates, Map.of());

            BigDecimal everyLine = lines.stream()
                    .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(amounts.total()).isEqualByComparingTo(everyLine);
            assertThat(amounts.charges().stream().map(MerchantAmounts::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(amounts.total());
            assertThat(amounts.charges()).extracting(MerchantAmounts::merchantId)
                    .containsExactlyElementsOf(lines.stream().map(PricedLine::merchantId).distinct().toList());
            for (MerchantAmounts charge : amounts.charges()) {
                BigDecimal ofItsLines = lines.stream().filter(line -> line.merchantId() == charge.merchantId())
                        .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(charge.subtotal()).isEqualByComparingTo(ofItsLines);
                assertThat(charge.amount()).isEqualByComparingTo(charge.subtotal());
                assertThat(charge.commission())
                        .isEqualTo(Amounts.commission(charge.amount(), rates.get(charge.merchantId())));
            }
            assertThat(amounts.total().scale()).isEqualTo(2);
        }
    }

    /**
     * The largest valid cart: 100 lines of 1,000 at 99,999.99, one merchant at
     * 100.00%. Mutation: the quantity left out of the line amount.
     */
    @Test
    void theLargestCartFitsPaymentServicesMaximum() {
        List<PricedLine> lines = new ArrayList<>();
        for (long product = 1; product <= 100; product++) {
            lines.add(line(product, 3, "99999.99", 1000));
        }

        Amounts amounts = Amounts.of(lines, Map.of(3L, new BigDecimal("100.00")), Map.of());

        assertThat(amounts.total()).isEqualTo(new BigDecimal("9999999000.00"));
        assertThat(amounts.charges().getFirst().commission()).isEqualTo(new BigDecimal("9999999000.00"));
        assertThat(amounts.total()).isLessThanOrEqualTo(new BigDecimal("9999999999.99"));
    }

    /**
     * A merchant whose discount covers its subtotal gets no charge leg (no
     * zero-amount authorization), and the other merchant is charged as usual.
     * Mutation: {@code amount.signum() < 0} instead of {@code <= 0}.
     */
    @Test
    void aMerchantWithNothingToPayGetsNoChargeLeg() {
        Amounts amounts = Amounts.of(List.of(line(1, 3, "10.00", 1), line(2, 4, "5.00", 2)), FIVE_PERCENT,
                Map.of(3L, new BigDecimal("10.00")));

        assertThat(amounts.charges()).extracting(MerchantAmounts::merchantId).containsExactly(4L);
        assertThat(amounts.total()).isEqualTo(new BigDecimal("10.00"));
        assertThat(amounts.lines()).hasSize(2);
    }

    /** Mutation: a missing rate taken as 0.00 (the merchant would pay no commission). */
    @Test
    void aMerchantWithoutARateIsABug() {
        assertThatThrownBy(() -> Amounts.of(List.of(line(1, 9, "10.00", 1)), FIVE_PERCENT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
