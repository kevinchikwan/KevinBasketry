package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409 {@code MERCHANT_NOT_LINKED}: {@code active} was sent for a PENDING
 * merchant, even with its current value. Otherwise identity-service's
 * {@code merchantActive = true} seed at link time would silently undo a
 * deactivation made before the link (DESIGN.md Section 2).
 */
public class MerchantNotLinkedException extends ErrorResponseException {

    public MerchantNotLinkedException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This merchant's login is not linked yet, so active cannot be changed. Retry the link first.");
        problem.setProperty("code", "MERCHANT_NOT_LINKED");
        return problem;
    }
}
