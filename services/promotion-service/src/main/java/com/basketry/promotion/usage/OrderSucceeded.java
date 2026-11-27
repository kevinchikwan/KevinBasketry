package com.basketry.promotion.usage;

import com.basketry.promotion.coupon.CouponCode;
import java.time.Instant;
import java.util.UUID;

/**
 * What the usage count needs from an order.succeeded event (DESIGN.md Section
 * 5). The merchants' discounts are checked by the parser but not kept: the
 * count does not depend on them (REQUIREMENTS Section 9).
 *
 * @param couponCode the normalised code the order carried, or null for none
 */
public record OrderSucceeded(UUID eventId, long orderId, CouponCode couponCode, Instant occurredAt) {
}
