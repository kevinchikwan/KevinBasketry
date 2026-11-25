package com.basketry.identity.merchantlink;

import com.basketry.identity.merchantlink.MerchantLinkResponse.Outcome;
import com.basketry.identity.web.RequestBodyReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/**
 * Internal only, called by merchant-service during onboarding. Reached only
 * with the shared API key (SecurityConfig's /internal/** chain), never with a
 * user JWT. 201 when a new account was created, 200 otherwise. The body is
 * read and validated by RequestBodyReader, so neither the email nor the
 * initial password reaches Spring's log (ROADMAP F2).
 */
@RestController
public class MerchantLinkController {

    private final MerchantLinkService service;
    private final RequestBodyReader bodies;

    public MerchantLinkController(MerchantLinkService service, RequestBodyReader bodies) {
        this.service = service;
        this.bodies = bodies;
    }

    @PostMapping("/internal/accounts/merchant-link")
    public ResponseEntity<MerchantLinkResponse> link(@RequestBody(required = false) byte[] body,
                                                     HttpServletRequest http)
            throws HttpMediaTypeNotSupportedException {
        MerchantLinkRequest request = bodies.read(http, body, MerchantLinkRequest.class);
        MerchantLinkResponse response = service.link(request.email(), request.merchantId(), request.initialPassword());
        HttpStatus status = response.outcome() == Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
