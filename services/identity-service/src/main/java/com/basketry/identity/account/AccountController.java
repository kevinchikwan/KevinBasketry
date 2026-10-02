package com.basketry.identity.account;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class AccountController {

    private final AccountRegistrationService registrationService;

    public AccountController(AccountRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * Public buyer self-registration. Returns 201 with a Location header and no
     * body; the account's representation will come from GET /accounts/{id}.
     */
    @PostMapping("/accounts/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterAccountRequest request) {
        Account account = registrationService.registerBuyer(request.email(), request.password());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/accounts/{id}")
                .buildAndExpand(account.getId())
                .toUri();
        return ResponseEntity.created(location).build();
    }
}
