package com.basketry.identity.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: the email is already used by an account or an admin account. */
public class EmailAlreadyRegisteredException extends ErrorResponseException {

    public EmailAlreadyRegisteredException(Throwable cause) {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email already exists."),
                cause);
    }
}
