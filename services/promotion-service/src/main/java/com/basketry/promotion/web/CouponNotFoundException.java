package com.basketry.promotion.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 404, for Admin only: anyone else gets CouponAccessDeniedException for a missing coupon. */
class CouponNotFoundException extends ErrorResponseException {

    CouponNotFoundException() {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No coupon has this id."),
                null);
    }
}
