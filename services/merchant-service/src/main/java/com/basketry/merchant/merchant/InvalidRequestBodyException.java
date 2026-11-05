package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 400: a request body that bean validation cannot catch: an unknown field,
 * or for PATCH an empty body or an explicit null.
 */
public class InvalidRequestBodyException extends ErrorResponseException {

    public InvalidRequestBodyException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
