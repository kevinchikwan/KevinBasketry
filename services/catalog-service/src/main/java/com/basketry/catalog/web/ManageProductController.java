package com.basketry.catalog.web;

import com.basketry.catalog.auth.TokenCapabilities;
import com.basketry.catalog.product.ManagedProduct;
import com.basketry.catalog.product.ProductReads;
import com.basketry.catalog.product.ProductReads.ManagedPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Merchant portal's and Admin's reads (FR-M2, FR-M3; DESIGN.md Section 3,
 * catalog-service): inactive products included, with their stock-row status,
 * never cached. As inventory-service's GET /stock: the parameters are checked
 * first (400 for anyone), then the capability.
 */
@RestController
public class ManageProductController {

    private final ProductReads reads;

    public ManageProductController(ProductReads reads) {
        this.reads = reads;
    }

    /**
     * Admin: every product, or one merchant's with {@code merchantId}. A
     * merchant: its own only; a {@code merchantId} other than its own is a 403.
     * A buyer-only token is a 403. Paging as GET /accounts, in id order.
     */
    @GetMapping("/manage/products")
    public Page list(@RequestParam(defaultValue = "0") @Min(0) int page,
                     @RequestParam(defaultValue = "" + PublicProductController.DEFAULT_PAGE_SIZE) @Min(1)
                     @Max(PublicProductController.MAX_PAGE_SIZE) int size,
                     @RequestParam(required = false) @Min(1) Long merchantId,
                     @AuthenticationPrincipal Jwt jwt) {
        OptionalLong scope;
        if (TokenCapabilities.isAdmin(jwt)) {
            scope = merchantId == null ? OptionalLong.empty() : OptionalLong.of(merchantId);
        } else {
            OptionalLong own = TokenCapabilities.merchantId(jwt);
            if (own.isEmpty() || (merchantId != null && merchantId != own.getAsLong())) {
                throw ProductAccessDeniedException.view();
            }
            scope = own;
        }
        return Page.of(reads.managedPage(scope, page, size), page, size);
    }

    /**
     * Admin, or the merchant that owns it. Ownership is known only after the
     * lookup, so a non-admin gets 403 both for another merchant's product and
     * for one that does not exist; only an admin gets a 404.
     */
    @GetMapping("/manage/products/{id}")
    public ManagedProduct get(@PathVariable @Min(1) long id, @AuthenticationPrincipal Jwt jwt) {
        if (TokenCapabilities.isAdmin(jwt)) {
            return reads.managed(id).orElseThrow(ProductNotFoundException::new);
        }
        return reads.managed(id)
                .filter(product -> TokenCapabilities.isMerchant(jwt, product.merchantId()))
                .orElseThrow(ProductAccessDeniedException::view);
    }

    /** The paging shape of GET /accounts. */
    public record Page(List<ManagedProduct> content, int page, int size, long totalElements, int totalPages) {

        static Page of(ManagedPage found, int page, int size) {
            return new Page(found.content(), page, size, found.totalElements(),
                    (int) ((found.totalElements() + size - 1) / size));
        }
    }
}
