package com.basketry.merchant.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 404: no merchant has this code. Only an Admin ever gets it. */
public class MerchantNotFoundException extends ErrorResponseException {

    public MerchantNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No merchant has this code."), null);
    }
}
