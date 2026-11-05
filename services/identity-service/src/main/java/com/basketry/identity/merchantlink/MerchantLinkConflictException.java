package com.basketry.identity.merchantlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: the link cannot be made. The {@code code} property says which rule refused it. */
public class MerchantLinkConflictException extends ErrorResponseException {

    public enum Code {
        EMAIL_BELONGS_TO_ADMIN("This email belongs to an admin account."),
        MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT("This merchant is already linked to a different account."),
        ACCOUNT_LINKED_TO_OTHER_MERCHANT("This account is already linked to a different merchant."),
        BUYER_DEACTIVATED("This buyer account is deactivated. Reactivate the buyer account first, then retry onboarding.");

        private final String detail;

        Code(String detail) {
            this.detail = detail;
        }
    }

    public MerchantLinkConflictException(Code code) {
        super(HttpStatus.CONFLICT, problem(code), null);
    }

    private static ProblemDetail problem(Code code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, code.detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
