package com.basketry.promotion.web;

import com.basketry.promotion.auth.TokenCapabilities;
import com.basketry.promotion.coupon.Coupon;
import java.util.OptionalLong;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who calls a coupon endpoint: Admin, or a merchant with its merchant id from
 * the token (never from the request). Anyone else is a 403 before any body is
 * read.
 */
record Caller(boolean admin, long merchantId) {

    static Caller of(Jwt jwt) {
        if (TokenCapabilities.isAdmin(jwt)) {
            return new Caller(true, 0);
        }
        OptionalLong merchantId = TokenCapabilities.merchantId(jwt);
        if (merchantId.isEmpty()) {
            throw CouponAccessDeniedException.notAManager();
        }
        return new Caller(false, merchantId.getAsLong());
    }

    /** Admin sees every coupon; a merchant only its own merchant coupons. */
    boolean mayRead(Coupon coupon) {
        return admin || coupon.getMerchantId() != null && coupon.getMerchantId() == merchantId;
    }

    /** The same rule as reading: Admin any coupon, a merchant only its own merchant coupons (FR-M11, FR-A10). */
    boolean mayDeactivate(Coupon coupon) {
        return mayRead(coupon);
    }
}
