package com.basketry.order.checkout;

import com.basketry.order.order.Amounts.PricedLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Where promotion-service's client will plug in (DESIGN.md Section 2,
 * order-service, coupons): each merchant's discount for a coupon. The only
 * implementation today is {@link NoCouponsYet}: a coupon is refused before this
 * is called (400 COUPONS_NOT_SUPPORTED).
 */
public interface CouponValidator {

    /**
     * @param couponCode null for no coupon
     * @param lines      the priced lines of the cart
     * @return each merchant's discount; a merchant not in the map has none
     */
    Map<Long, BigDecimal> discounts(String couponCode, List<PricedLine> lines);
}
