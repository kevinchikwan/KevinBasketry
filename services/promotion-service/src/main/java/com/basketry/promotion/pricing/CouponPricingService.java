package com.basketry.promotion.pricing;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a coupon for a cart (DESIGN.md Section 2, promotion-service): finds it
 * by its normalised code, refuses it with a {@link CouponRefusal}, or answers
 * the discount for every merchant ({@link DiscountCalculator}).
 *
 * <p>The checks run in DESIGN's order, and the first that applies wins:
 * {@code COUPON_NOT_FOUND}, {@code COUPON_INACTIVE}, {@code COUPON_EXPIRED}
 * (now at or after expiresAt, by the injected clock), {@code COUPON_NOT_APPLICABLE}
 * (a merchant coupon whose merchant is not in the cart). A platform coupon is
 * always applicable. A bad cart is checked before the lookup and is an
 * {@link IllegalArgumentException}, never a refusal.
 *
 * <p>Read-only: pricing a coupon writes nothing and does not count it. The code
 * is logged only at DEBUG.
 */
@Service
public class CouponPricingService {

    private static final Logger log = LoggerFactory.getLogger(CouponPricingService.class);

    private final CouponRepository coupons;
    private final Clock clock;

    public CouponPricingService(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    /**
     * @param code an already normalised code (the endpoint normalises what arrives)
     * @param cart every merchant in the cart, each once, in any order
     * @return one discount per merchant, in the order given, 0.00 where the coupon does not apply
     * @throws CouponRefusedException   when the coupon cannot be used for this cart, with the reason
     * @throws IllegalArgumentException for a missing code, or a missing or empty cart, an empty entry
     *                                  or a merchant given twice
     */
    @Transactional(readOnly = true)
    public List<MerchantDiscount> price(CouponCode code, List<MerchantSubtotal> cart) {
        if (code == null) {
            throw new IllegalArgumentException("A coupon code is needed.");
        }
        Set<Long> merchantIds = DiscountCalculator.checkCart(cart);
        Coupon coupon = coupons.findByCode(code).orElseThrow(() -> refused(code, CouponRefusal.COUPON_NOT_FOUND));
        if (!coupon.isActive()) {
            throw refused(code, CouponRefusal.COUPON_INACTIVE);
        }
        if (!clock.instant().isBefore(coupon.getExpiresAt())) {
            throw refused(code, CouponRefusal.COUPON_EXPIRED);
        }
        if (coupon.getScope() == CouponScope.MERCHANT && !merchantIds.contains(coupon.getMerchantId())) {
            throw refused(code, CouponRefusal.COUPON_NOT_APPLICABLE);
        }
        return DiscountCalculator.price(coupon.getDiscount(), coupon.getScope(), coupon.getMerchantId(), cart);
    }

    private static CouponRefusedException refused(CouponCode code, CouponRefusal reason) {
        log.debug("Coupon {} refused: {}", code, reason);
        return new CouponRefusedException(reason);
    }
}
