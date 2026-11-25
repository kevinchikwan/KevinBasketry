package com.basketry.promotion.web;

import com.basketry.promotion.pricing.MerchantDiscount;
import java.math.BigDecimal;
import java.util.List;

/**
 * The 200 of POST /internal/coupons/price: one entry for every merchant sent,
 * in the order sent, each discount a JSON number at scale 2 ({@code 1.00},
 * never {@code 1.0} or {@code 1}).
 */
public record PriceResponse(List<Line> merchants) {

    static PriceResponse from(List<MerchantDiscount> discounts) {
        return new PriceResponse(discounts.stream().map(d -> new Line(d.merchantId(), d.discount())).toList());
    }

    public record Line(long merchantId, BigDecimal discount) {
    }
}
