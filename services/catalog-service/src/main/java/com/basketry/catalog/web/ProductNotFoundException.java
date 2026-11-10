package com.basketry.catalog.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 404: on the public detail, a product the listing hides (inactive, of an
 * inactive merchant) or one that does not exist, alike; on the management
 * detail, a missing product, which only an admin can learn.
 */
public class ProductNotFoundException extends ErrorResponseException {

    public ProductNotFoundException() {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "No such product, or it is not available."), null);
    }
}
