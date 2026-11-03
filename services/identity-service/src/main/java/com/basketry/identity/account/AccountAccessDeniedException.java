package com.basketry.identity.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 403: a non-admin token asked for an account other than its own. Thrown before
 * the account is looked up, so the caller cannot tell whether that id exists.
 */
public class AccountAccessDeniedException extends ErrorResponseException {

    public AccountAccessDeniedException() {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You may only view your own account."),
                null);
    }
}
