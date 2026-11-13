package com.basketry.order.checkout;

import com.basketry.order.order.Amounts.PricedLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Until promotion-service exists: no discounts. CheckoutCommand refuses a coupon code before this runs. */
@Component
class NoCouponsYet implements CouponValidator {

    @Override
    public Map<Long, BigDecimal> discounts(String couponCode, List<PricedLine> lines) {
        if (couponCode != null) {
            throw new IllegalStateException("A coupon reached checkout; CheckoutCommand refuses them");
        }
        return Map.of();
    }
}
