package com.basketry.merchant.merchant;

import com.basketry.merchant.auth.TokenCapabilities;
import jakarta.validation.Valid;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Merchant endpoints (DESIGN.md Section 3, merchant-service). As in
 * identity-service, request validation runs before the admin check: an
 * authenticated non-admin with an invalid request gets 400, not 403. Nothing
 * is changed or revealed either way.
 */
@RestController
public class MerchantController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final MerchantOnboardingService onboarding;
    private final MerchantUpdateService updates;
    private final MerchantRepository merchants;

    public MerchantController(MerchantOnboardingService onboarding, MerchantUpdateService updates,
                              MerchantRepository merchants) {
        this.onboarding = onboarding;
        this.updates = updates;
        this.merchants = merchants;
    }

    /**
     * Admin only (FR-A1). Always 201 once the merchant is saved, whether or
     * not its login could be linked; {@code accountLinkStatus} says which.
     */
    @PostMapping("/merchants")
    public ResponseEntity<OnboardingResponse> onboard(@Valid @RequestBody CreateMerchantRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        request.rejectUnknownFields();
        TokenCapabilities.requireAdmin(jwt);
        OnboardingResponse response = onboarding.onboard(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/merchants/{code}")
                .buildAndExpand(response.merchantCode())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /** Admin only: retries the login link of a PENDING merchant. The body is optional. */
    @PostMapping("/merchants/{code}/account-link")
    public OnboardingResponse retryAccountLink(@PathVariable String code,
                                               @Valid @RequestBody(required = false) AccountLinkRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        TokenCapabilities.requireAdmin(jwt);
        return onboarding.retryLink(code, request == null ? AccountLinkRequest.EMPTY : request);
    }

    /**
     * Admin only (FR-A2). Same paging as identity-service's GET /accounts:
     * {@code page} from 0, {@code size} 1..100 (default 20), anything out of
     * range is a 400, fixed id-ascending order.
     */
    @GetMapping("/merchants")
    @Transactional(readOnly = true)
    public MerchantPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size,
                             @AuthenticationPrincipal Jwt jwt) {
        TokenCapabilities.requireAdmin(jwt);
        return MerchantPage.from(merchants.findAll(PageRequest.of(page, size, Sort.by("id").ascending())));
    }

    /**
     * Admin, or the merchant itself (a MERCHANT token whose merchantId claim
     * is this merchant's id). Ownership can only be checked after the lookup,
     * so a non-admin gets 403 both for someone else's merchant and for a code
     * that does not exist; only an admin can get a 404.
     */
    @GetMapping("/merchants/{code}")
    @Transactional(readOnly = true)
    public MerchantResponse get(@PathVariable String code, @AuthenticationPrincipal Jwt jwt) {
        if (TokenCapabilities.isAdmin(jwt)) {
            return merchants.findByMerchantCode(code)
                    .map(MerchantResponse::from)
                    .orElseThrow(MerchantNotFoundException::new);
        }
        return merchants.findByMerchantCode(code)
                .filter(merchant -> TokenCapabilities.isMerchant(jwt, merchant.getId()))
                .map(MerchantResponse::from)
                .orElseThrow(MerchantAccessDeniedException::new);
    }

    /**
     * Admin only (FR-A3, FR-A4): commissionRate, kycVerified and/or active, applied
     * together or not at all. Only a real change of active publishes
     * merchant.status.changed; kycVerified is independent of it.
     */
    @PatchMapping("/merchants/{code}")
    public MerchantResponse update(@PathVariable String code,
                                   @Valid @RequestBody UpdateMerchantRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        request.checkShape();
        TokenCapabilities.requireAdmin(jwt);
        return updates.update(code, request);
    }
}
