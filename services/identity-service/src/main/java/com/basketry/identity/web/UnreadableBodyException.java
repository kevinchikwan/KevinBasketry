package com.basketry.identity.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A body that is not JSON of the request's shape: the 400 Spring Boot answered
 * for an unreadable body ("Failed to read request"), with no value from the
 * input in the answer, the message or a cause (RequestBodyReader).
 */
public class UnreadableBodyException extends ErrorResponseException {

    static final String DETAIL = "Failed to read request";

    UnreadableBodyException() {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, DETAIL), null);
    }
}
