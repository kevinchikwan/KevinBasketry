package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 503 {@code INTERNAL_API_KEY_NOT_CONFIGURED}: onboarding cannot call
 * identity-service because {@code BASKETRY_INTERNAL_API_KEY} is unset.
 * Answered before anything is saved, so no merchant is left PENDING with no
 * way to link.
 */
public class OnboardingUnavailableException extends ErrorResponseException {

    public OnboardingUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Merchant onboarding is unavailable: the internal API key is not configured.");
        problem.setProperty("code", "INTERNAL_API_KEY_NOT_CONFIGURED");
        return problem;
    }
}
