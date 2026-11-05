package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 403 for a non-admin token on GET /merchants/{code}, both when the merchant
 * is someone else's and when it does not exist, so it reveals neither.
 */
public class MerchantAccessDeniedException extends ErrorResponseException {

    public MerchantAccessDeniedException() {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                        "Only an admin or the merchant itself can view this merchant."), null);
    }
}
