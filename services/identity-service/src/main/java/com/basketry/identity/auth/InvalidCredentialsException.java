package com.basketry.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 401: unknown email or wrong password. Both cases get the same response so
 * the endpoint cannot be used to find out which emails are registered.
 */
public class InvalidCredentialsException extends ErrorResponseException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password."),
                null);
    }
}
