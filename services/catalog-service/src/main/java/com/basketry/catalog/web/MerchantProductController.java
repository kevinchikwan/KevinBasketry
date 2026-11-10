package com.basketry.catalog.web;

import com.basketry.catalog.auth.TokenCapabilities;
import com.basketry.catalog.product.ManagedProduct;
import com.basketry.catalog.product.ProductChanges;
import com.basketry.catalog.product.ProductReads;
import com.basketry.catalog.product.ProductService;
import com.basketry.catalog.product.ProductView;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.OptionalLong;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A merchant's writes (FR-M2, FR-M3; DESIGN.md Section 3, catalog-service).
 * Only a token with the MERCHANT capability, for its own products: the owner
 * comes from the token's {@code merchantId} claim, never from the request.
 * Admin can read every product (/manage) but change none. The body is checked
 * before the capability: an invalid request is a 400 for anyone, never a 403.
 */
@RestController
public class MerchantProductController {

    private final ProductService products;
    private final ProductReads reads;

    public MerchantProductController(ProductService products, ProductReads reads) {
        this.products = products;
        this.reads = reads;
    }

    /** 201, with Location at /manage/products/{id}: the public URL hides the product while its merchant is inactive. */
    @PostMapping("/products")
    public ResponseEntity<ManagedProduct> create(@RequestBody CreateProductRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        request.check();
        long merchantId = ownMerchant(jwt);
        ProductView created = products.create(request.toCommand(merchantId));
        return ResponseEntity.created(URI.create("/manage/products/" + created.id())).body(managed(created.id()));
    }

    /**
     * 200 with the product after the change; values that do not change are a
     * 200 no-op. Another merchant's product and a missing one are both a 403.
     * 503 PRODUCT_BUSY if another request holds the product for more than 5 s.
     */
    @PatchMapping("/products/{id}")
    public ManagedProduct update(@PathVariable @Min(1) long id, @RequestBody UpdateProductRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        ProductChanges changes = request.toChanges();
        products.update(ownMerchant(jwt), id, changes);
        return managed(id);
    }

    private static long ownMerchant(Jwt jwt) {
        OptionalLong merchantId = TokenCapabilities.merchantId(jwt);
        if (merchantId.isEmpty()) {
            throw ProductAccessDeniedException.write();
        }
        return merchantId.getAsLong();
    }

    private ManagedProduct managed(long id) {
        return reads.managed(id).orElseThrow();
    }
}
