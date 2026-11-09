package com.basketry.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400: a request body that bean validation cannot catch, such as an unknown field. */
public class InvalidRequestBodyException extends ErrorResponseException {

    public InvalidRequestBodyException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
