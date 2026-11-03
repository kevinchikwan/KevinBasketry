package com.basketry.identity.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 404: no account with that id. Only an admin can get this far for an id that is not their own. */
public class AccountNotFoundException extends ErrorResponseException {

    public AccountNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account not found."),
                null);
    }
}
