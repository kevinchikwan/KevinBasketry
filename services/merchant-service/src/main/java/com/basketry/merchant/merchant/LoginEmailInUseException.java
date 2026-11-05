package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409 {@code LOGIN_EMAIL_IN_USE}: another merchant already has this login
 * email. One account holds at most one merchant, so the two could never both
 * be linked. {@code merchantCode} names the merchant that has it, so Admin
 * can retry that one instead of creating a duplicate.
 */
public class LoginEmailInUseException extends ErrorResponseException {

    public LoginEmailInUseException(String holderMerchantCode) {
        super(HttpStatus.CONFLICT, problem(holderMerchantCode), null);
    }

    private static ProblemDetail problem(String holderMerchantCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Merchant " + holderMerchantCode + " already uses this login email.");
        problem.setProperty("code", "LOGIN_EMAIL_IN_USE");
        problem.setProperty("merchantCode", holderMerchantCode);
        return problem;
    }
}
