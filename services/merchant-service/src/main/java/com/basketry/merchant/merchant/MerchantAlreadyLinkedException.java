package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409 {@code MERCHANT_ALREADY_LINKED}: account-link was called for a merchant that is already LINKED. */
public class MerchantAlreadyLinkedException extends ErrorResponseException {

    public MerchantAlreadyLinkedException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This merchant's login is already linked.");
        problem.setProperty("code", "MERCHANT_ALREADY_LINKED");
        return problem;
    }
}
