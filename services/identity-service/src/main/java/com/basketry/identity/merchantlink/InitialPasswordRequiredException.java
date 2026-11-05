package com.basketry.identity.merchantlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400: no account has this email, so one must be created, but no initialPassword was given. */
public class InitialPasswordRequiredException extends ErrorResponseException {

    public InitialPasswordRequiredException() {
        super(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                        "initialPassword is required when no account exists for this email."),
                null);
    }
}
