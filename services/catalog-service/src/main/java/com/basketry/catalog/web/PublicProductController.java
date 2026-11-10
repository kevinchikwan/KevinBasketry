package com.basketry.catalog.web;

import com.basketry.catalog.listing.ListedProduct;
import com.basketry.catalog.listing.ProductListPage;
import com.basketry.catalog.listing.ProductListing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Buyer marketplace's reads (FR-B3, FR-B6; DESIGN.md Section 3,
 * catalog-service). Public: in their own security chain, which never reads a
 * token. Only visible products: active, of an active merchant.
 */
@RestController
public class PublicProductController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final ProductListing listing;

    public PublicProductController(ProductListing listing) {
        this.listing = listing;
    }

    /**
     * Paging as GET /accounts: {@code page} from 0, {@code size} 1 to 100,
     * anything out of range a 400, id order, a page past the end 200 with empty
     * {@code content}. {@code merchantId} positive; {@code category} an exact,
     * case-sensitive match, 1 to 50 characters.
     */
    @GetMapping("/products")
    public ProductListPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size,
                                @RequestParam(required = false) @Min(1) Long merchantId,
                                @RequestParam(required = false) @Size(min = 1, max = 50) String category) {
        return listing.page(merchantId == null ? OptionalLong.empty() : OptionalLong.of(merchantId),
                Optional.ofNullable(category), page, size);
    }

    /** 404 for a product the listing hides, as for one that does not exist. */
    @GetMapping("/products/{id}")
    public ListedProduct get(@PathVariable @Min(1) long id) {
        return listing.product(id).orElseThrow(ProductNotFoundException::new);
    }
}
