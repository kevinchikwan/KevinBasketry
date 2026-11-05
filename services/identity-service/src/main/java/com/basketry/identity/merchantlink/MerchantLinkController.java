package com.basketry.identity.merchantlink;

import com.basketry.identity.merchantlink.MerchantLinkResponse.Outcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal only, called by merchant-service during onboarding. Reached only
 * with the shared API key (SecurityConfig's /internal/** chain), never with a
 * user JWT. 201 when a new account was created, 200 otherwise.
 */
@RestController
public class MerchantLinkController {

    private final MerchantLinkService service;

    public MerchantLinkController(MerchantLinkService service) {
        this.service = service;
    }

    @PostMapping("/internal/accounts/merchant-link")
    public ResponseEntity<MerchantLinkResponse> link(@Valid @RequestBody MerchantLinkRequest request) {
        MerchantLinkResponse response = service.link(request.email(), request.merchantId(), request.initialPassword());
        HttpStatus status = response.outcome() == Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
