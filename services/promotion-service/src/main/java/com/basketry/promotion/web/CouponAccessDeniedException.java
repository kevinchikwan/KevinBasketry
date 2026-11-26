package com.basketry.promotion.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 403 on the coupon endpoints. For a single coupon it is also the answer when
 * the coupon does not exist, so a merchant cannot tell another merchant's
 * coupon, or a platform coupon, from a missing one; only Admin gets a 404 (as
 * catalog-, inventory- and merchant-service).
 */
class CouponAccessDeniedException extends ErrorResponseException {

    private CouponAccessDeniedException(String detail) {
        super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail), null);
    }

    /** A token that is neither Admin's nor a merchant's (with a merchant id above 0). */
    static CouponAccessDeniedException notAManager() {
        return new CouponAccessDeniedException("Only Admin and merchants can manage coupons.");
    }

    /** A merchant's platform coupon, or a coupon for another merchant. */
    static CouponAccessDeniedException notItsOwnToCreate() {
        return new CouponAccessDeniedException("A merchant can create only its own MERCHANT coupons.");
    }

    /** Another merchant's coupon, a platform coupon, or none: nothing is changed. */
    static CouponAccessDeniedException notItsOwnToDeactivate() {
        return new CouponAccessDeniedException("Only Admin and the coupon's merchant can deactivate this coupon.");
    }

    /** Another merchant's coupon, a platform coupon, or none. */
    static CouponAccessDeniedException notItsOwnToSee() {
        return new CouponAccessDeniedException("Only Admin and the coupon's merchant can see this coupon.");
    }
}
