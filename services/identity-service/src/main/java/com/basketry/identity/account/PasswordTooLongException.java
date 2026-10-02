package com.basketry.identity.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 400: BCrypt only uses the first 72 bytes of a password, and Spring's encoder
 * refuses longer input, so it is rejected up front instead of failing with a 500.
 */
public class PasswordTooLongException extends ErrorResponseException {

    public PasswordTooLongException() {
        super(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Password must be at most 72 bytes."),
                null);
    }
}
