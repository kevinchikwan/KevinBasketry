package com.basketry.promotion.web;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A coupon as the coupon endpoints answer it (DESIGN.md Section 3,
 * promotion-service). {@code discountValue} at scale 2; {@code merchantId}
 * null for a platform coupon. {@code active} is the stored flag, which expiry
 * never changes; {@code expired} is read at answer time against the service
 * clock, the end exclusive ({@code now >= expiresAt}), as pricing reads it. A
 * coupon is accepted at checkout only when it is active and not expired.
 * {@code usageCount} is the number of orders counted for the coupon from
 * order.succeeded (ROADMAP A8); it never affects pricing.
 */
public record CouponResponse(long id, String code, CouponScope scope, Long merchantId, DiscountType discountType,
                             BigDecimal discountValue, Instant expiresAt, boolean active, boolean expired,
                             long usageCount) {

    static CouponResponse from(Coupon coupon, Instant now, long usageCount) {
        return new CouponResponse(coupon.getId(), coupon.getCode().value(), coupon.getScope(),
                coupon.getMerchantId(), coupon.getDiscount().type(), coupon.getDiscount().value(),
                coupon.getExpiresAt(), coupon.isActive(), !now.isBefore(coupon.getExpiresAt()), usageCount);
    }
}
