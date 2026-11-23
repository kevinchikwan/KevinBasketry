package com.basketry.promotion.pricing;

import java.math.BigDecimal;

/** A coupon's discount for one merchant, at scale 2: 0.00 up to that merchant's subtotal. */
public record MerchantDiscount(long merchantId, BigDecimal discount) {
}
