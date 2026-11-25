package com.basketry.identity.account;

import com.basketry.identity.auth.TokenCapabilities;
import com.basketry.identity.web.RequestBodyReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class AccountController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final AccountRegistrationService registrationService;
    private final AccountRepository accounts;
    private final RequestBodyReader bodies;

    public AccountController(AccountRegistrationService registrationService, AccountRepository accounts,
                             RequestBodyReader bodies) {
        this.registrationService = registrationService;
        this.accounts = accounts;
        this.bodies = bodies;
    }

    /**
     * Public buyer self-registration. Returns 201 with a Location header and no
     * body; the account's representation comes from GET /accounts/{id}. The
     * body is read and validated by RequestBodyReader, so neither the email
     * nor the password reaches Spring's log (ROADMAP F2).
     */
    @PostMapping("/accounts/register")
    public ResponseEntity<Void> register(@RequestBody(required = false) byte[] body, HttpServletRequest http)
            throws HttpMediaTypeNotSupportedException {
        RegisterAccountRequest request = bodies.read(http, body, RegisterAccountRequest.class);
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
        if (!TokenCapabilities.isAdmin(jwt) && !Long.toString(id).equals(jwt.getSubject())) {
            throw new AccountAccessDeniedException();
        }
        return accounts.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(AccountNotFoundException::new);
    }

    /**
     * Admin only (FR-A5): every buyer account (merchant-linked ones included;
     * AdminAccount rows never appear). Fixed id-ascending order so paging is
     * stable. {@code page} starts at 0; {@code size} is 1..100, and anything
     * out of range is a 400 rather than silently clamped.
     */
    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    public AccountPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size,
                            @AuthenticationPrincipal Jwt jwt) {
        TokenCapabilities.requireAdmin(jwt);
        return AccountPage.from(accounts.findAll(PageRequest.of(page, size, Sort.by("id").ascending())));
    }

    /**
     * Admin only (FR-A6): activates or deactivates the buyer account, the
     * login gate for the whole account. The admin check comes before the
     * lookup, so only an admin can learn that an id does not exist (404).
     * Tokens already issued are not revoked; checkout re-checks buyerActive
     * live through GET /accounts/{id}. The body is read and validated first,
     * before the admin check, as when Spring read it before the method ran.
     */
    @PatchMapping("/accounts/{id}/buyer-status")
    @Transactional
    public AccountResponse setBuyerStatus(@PathVariable long id, @RequestBody(required = false) byte[] body,
                                          @AuthenticationPrincipal Jwt jwt, HttpServletRequest http)
            throws HttpMediaTypeNotSupportedException {
        BuyerStatusRequest request = bodies.read(http, body, BuyerStatusRequest.class);
        TokenCapabilities.requireAdmin(jwt);
        Account account = accounts.findById(id).orElseThrow(AccountNotFoundException::new);
        account.setBuyerActive(request.buyerActive());
        return AccountResponse.from(account);
    }
}
