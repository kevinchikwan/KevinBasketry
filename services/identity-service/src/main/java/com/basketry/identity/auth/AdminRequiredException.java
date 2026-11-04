package com.basketry.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 403: a valid token without the ADMIN capability called an Admin-only endpoint. */
public class AdminRequiredException extends ErrorResponseException {

    public AdminRequiredException() {
        super(HttpStatus.FORBIDDEN,
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Admin access is required."),
                null);
    }
}
