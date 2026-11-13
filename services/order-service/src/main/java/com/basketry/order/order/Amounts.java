package com.basketry.order.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The money of one checkout (DESIGN.md Section 2, order-service, amounts and
 * commission). Pure arithmetic, no I/O:
 *
 * <ul>
 *   <li>{@code lineAmount = unitPrice x quantity}, exact: both have at most 2
 *       decimal places, and a quantity is whole.</li>
 *   <li>A merchant's {@code subtotal} is the sum of its lines,
 *       {@code amount = subtotal - discount}, and
 *       {@code commission = amount x commissionRate / 100}, rounded HALF_UP to 2
 *       decimal places: on the amount charged, after the discount.</li>
 *   <li>{@code total} is the sum of the charged amounts, so the merchant
 *       charges always sum to the order total.</li>
 * </ul>
 *
 * <p>A merchant whose amount comes to zero gets no charge leg (DESIGN.md Section
 * 2, promotion-service). Discounts are all zero until promotion-service exists,
 * so today every merchant has one.
 */
public record Amounts(List<LineAmounts> lines, List<MerchantAmounts> charges, BigDecimal total) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * @param lines     the cart's lines, in cart order, priced from catalog-service
     * @param rates     each merchant's commission rate, a percentage at scale 2
     * @param discounts each merchant's discount; a merchant not in the map has none
     */
    public static Amounts of(List<PricedLine> lines, Map<Long, BigDecimal> rates, Map<Long, BigDecimal> discounts) {
        List<LineAmounts> lineAmounts = new ArrayList<>();
        Map<Long, BigDecimal> subtotals = new LinkedHashMap<>();
        for (PricedLine line : lines) {
            BigDecimal lineAmount = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).setScale(2);
            lineAmounts.add(new LineAmounts(line.productId(), line.merchantId(), line.productName(),
                    line.unitPrice(), line.quantity(), lineAmount));
            subtotals.merge(line.merchantId(), lineAmount, BigDecimal::add);
        }
        List<MerchantAmounts> charges = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<Long, BigDecimal> merchant : subtotals.entrySet()) {
            long merchantId = merchant.getKey();
            BigDecimal subtotal = merchant.getValue();
            BigDecimal discount = discounts.getOrDefault(merchantId, BigDecimal.ZERO).setScale(2);
            BigDecimal amount = subtotal.subtract(discount);
            if (amount.signum() <= 0) {
                continue;
            }
            BigDecimal rate = rates.get(merchantId);
            if (rate == null) {
                throw new IllegalArgumentException("No commission rate for merchant " + merchantId);
            }
            charges.add(new MerchantAmounts(merchantId, subtotal, discount, amount, rate, commission(amount, rate)));
            total = total.add(amount);
        }
        return new Amounts(List.copyOf(lineAmounts), List.copyOf(charges), total);
    }

    /** {@code amount x rate / 100}, HALF_UP, 2 decimal places. */
    static BigDecimal commission(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** One cart line with catalog-service's current price and name. */
    public record PricedLine(long productId, long merchantId, String productName, BigDecimal unitPrice,
                             int quantity) {
    }

    public record LineAmounts(long productId, long merchantId, String productName, BigDecimal unitPrice,
                              int quantity, BigDecimal lineAmount) {
    }

    public record MerchantAmounts(long merchantId, BigDecimal subtotal, BigDecimal discount, BigDecimal amount,
                                  BigDecimal commissionRate, BigDecimal commission) {
    }
}
