package com.basketry.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 403: the password was correct but the account has {@code buyerActive = false}
 * (DESIGN.md Section 2, login rules). Only thrown after the password check, so
 * it never tells a caller without the password anything about the account.
 */
public class AccountDeactivatedException extends ErrorResponseException {

    public AccountDeactivatedException() {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "This account has been deactivated."),
                null);
    }
}
