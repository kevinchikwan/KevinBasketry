package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 503 {@code MERCHANT_BUSY}: the merchant's row stayed locked by another
 * transaction for longer than {@link MerchantRepository#LOCK_TIMEOUT_MILLIS}.
 * This request changed nothing in merchant-service, so the client can simply
 * try again.
 */
public class MerchantBusyException extends ErrorResponseException {

    /** For a PATCH: the client already knows the merchant. */
    public MerchantBusyException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(null), cause);
    }

    /**
     * For a link result that could not be recorded: the merchant exists and is
     * still PENDING. Its code is included, because a new merchant's client does
     * not know it yet and needs it to retry the link.
     */
    public MerchantBusyException(String merchantCode, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(merchantCode), cause);
    }

    private static ProblemDetail problem(String merchantCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                merchantCode == null
                        ? "The merchant is being changed by another request. Try again."
                        : "The merchant is saved, but its login link could not be recorded because another"
                                + " request is changing it. Retry the link.");
        problem.setProperty("code", "MERCHANT_BUSY");
        if (merchantCode != null) {
            problem.setProperty("merchantCode", merchantCode);
        }
        return problem;
    }
}
