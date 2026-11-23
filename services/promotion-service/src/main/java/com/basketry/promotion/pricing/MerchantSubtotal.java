package com.basketry.promotion.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One merchant's part of a cart, as order-service sends it to be priced
 * (DESIGN.md Section 3, promotion-service): a merchant id above 0 and a
 * subtotal above 0 with at most 2 decimal places, held at scale 2.
 */
public record MerchantSubtotal(long merchantId, BigDecimal subtotal) {

    public MerchantSubtotal {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("A merchant id is above 0.");
        }
        if (subtotal == null || subtotal.signum() <= 0) {
            throw new IllegalArgumentException("A subtotal is above 0.");
        }
        if (subtotal.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("A subtotal has at most 2 decimal places.");
        }
        // Exact: the scale check above means no digit is lost.
        subtotal = subtotal.setScale(2, RoundingMode.UNNECESSARY);
    }
}
