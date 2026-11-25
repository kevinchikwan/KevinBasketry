package com.basketry.identity.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A body that breaks a constraint of its request type: the 400 Spring Boot
 * answered for a failed {@code @Valid} check ("Invalid request content."),
 * without the field errors, whose rejected values Spring logged at DEBUG
 * (RequestBodyReader).
 */
public class InvalidBodyException extends ErrorResponseException {

    static final String DETAIL = "Invalid request content.";

    InvalidBodyException() {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, DETAIL), null);
    }
}
