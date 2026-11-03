package com.basketry.identity.account;

import com.basketry.identity.auth.Capability;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class AccountController {

    private final AccountRegistrationService registrationService;
    private final AccountRepository accounts;

    public AccountController(AccountRegistrationService registrationService, AccountRepository accounts) {
        this.registrationService = registrationService;
        this.accounts = accounts;
    }

    /**
     * Public buyer self-registration. Returns 201 with a Location header and no
     * body; the account's representation comes from GET /accounts/{id}.
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

    /**
     * Self or Admin (DESIGN.md Section 3). An ADMIN token skips the sub
     * comparison entirely: Account and AdminAccount ids are separate sequences
     * that both start at 1, so an admin's sub can equal some buyer's id and
     * must never be compared against the path. Any other token must have sub
     * equal to {@code id}; otherwise 403, decided before the lookup so it does
     * not reveal whether the account exists.
     */
    @GetMapping("/accounts/{id}")
    @Transactional(readOnly = true)
    public AccountResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        if (!isAdmin(jwt) && !Long.toString(id).equals(jwt.getSubject())) {
            throw new AccountAccessDeniedException();
        }
        return accounts.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(AccountNotFoundException::new);
    }

    private static boolean isAdmin(Jwt jwt) {
        List<String> capabilities = jwt.getClaimAsStringList("capabilities");
        return capabilities != null && capabilities.contains(Capability.ADMIN.name());
    }
}
