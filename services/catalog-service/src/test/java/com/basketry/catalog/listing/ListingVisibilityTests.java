package com.basketry.catalog.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.product.ProductChanges;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Rows 3 and 5 of the Day 12 review: what the public listing shows (FR-B3,
 * FR-B6). Hidden: inactive products, and products of inactive merchants. Shown:
 * products with no stock, as OUT_OF_STOCK.
 */
class ListingVisibilityTests extends CatalogServiceTestBase {

    private static ProductChanges active(boolean value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(value));
    }

    /**
     * No event has ever been seen for this merchant, so it has no
     * merchant_status row: it is active at version 0. Mutation:
     * COALESCE(ms.active, FALSE) (every new merchant's products hidden until its
     * first status change).
     */
    @Test
    void aMerchantThatNeverChangedStatusIsActive() {
        givenProduct(MERCHANT, "Tea");

        assertThat(merchantStatusRepository.count()).isZero();
        assertThat(names(publicListing())).containsExactly("Tea");
    }

    /** Mutation: drop "p.active" from the visibility rule. */
    @Test
    void anInactiveProductIsHidden() {
        long tea = givenProduct(MERCHANT, "Tea").id();
        givenProduct(MERCHANT, "Coffee");

        productService.update(MERCHANT, tea, active(false));

        assertThat(names(publicListing())).containsExactly("Coffee");
        productService.update(MERCHANT, tea, active(true));
        assertThat(names(publicListing())).containsExactly("Tea", "Coffee");
    }

    /** FR-A3 / FR-B6. Mutation: drop the merchant_status join from the visibility rule. */
    @Test
    void theProductsOfAnInactiveMerchantAreHiddenUntilItIsActiveAgain() {
        givenProduct(MERCHANT, "Tea");
        givenProduct(OTHER_MERCHANT, "Coffee");

        merchantStatuses.apply(MERCHANT, false, 1);
        assertThat(names(publicListing())).containsExactly("Coffee");
        assertThat(names(publicListingOf(MERCHANT))).isEmpty();

        merchantStatuses.apply(MERCHANT, true, 2);
        assertThat(names(publicListing())).containsExactly("Tea", "Coffee");
    }

    /** FR-B6: shown as unavailable, not hidden. Mutation: hide products without stock. */
    @Test
    void aProductWithNoStockIsListedAsOutOfStock() {
        long tea = givenProduct(MERCHANT, "Tea").id();
        long coffee = givenProduct(MERCHANT, "Coffee").id();
        long cocoa = givenProduct(MERCHANT, "Cocoa").id();
        inventory.givenStock(tea, MERCHANT, 5);
        inventory.givenStock(coffee, MERCHANT, 0);

        assertThat(publicListing().content()).extracting(ListedProduct::id, ListedProduct::availability)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(tea, Availability.IN_STOCK),
                        org.assertj.core.groups.Tuple.tuple(coffee, Availability.OUT_OF_STOCK),
                        org.assertj.core.groups.Tuple.tuple(cocoa, Availability.OUT_OF_STOCK));
    }

    /** Mutation: ignore the merchant filter, or filter by category with LIKE / case-insensitively. */
    @Test
    void theMerchantAndCategoryFiltersAreExact() {
        givenProduct(MERCHANT, "Tea");
        givenProduct(OTHER_MERCHANT, "Coffee");
        productService.create(new com.basketry.catalog.product.CreateProductCommand(MERCHANT, "Soap", "",
                "Household", new java.math.BigDecimal("1.00")));

        assertThat(names(publicListingOf(MERCHANT))).containsExactly("Tea", "Soap");
        assertThat(names(listing.page(OptionalLong.empty(), Optional.of("Household"), 0, 20)))
                .containsExactly("Soap");
        assertThat(names(listing.page(OptionalLong.empty(), Optional.of("household"), 0, 20))).isEmpty();
        assertThat(names(listing.page(OptionalLong.empty(), Optional.of("House"), 0, 20))).isEmpty();
        assertThat(names(listing.page(OptionalLong.of(OTHER_MERCHANT), Optional.of("Groceries"), 0, 20)))
                .containsExactly("Coffee");
    }

    /** The paging shape of GET /accounts. Mutation: an offset of page instead of page * size. */
    @Test
    void pagesFollowIdOrderAndCountOnlyVisibleProducts() {
        for (int i = 1; i <= 5; i++) {
            givenProduct(MERCHANT, "P" + i);
        }
        long hidden = givenProduct(MERCHANT, "Hidden").id();
        productService.update(MERCHANT, hidden, active(false));

        ProductListPage second = listing.page(OptionalLong.empty(), Optional.empty(), 1, 2);
        assertThat(names(second)).containsExactly("P3", "P4");
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);

        ProductListPage pastTheEnd = listing.page(OptionalLong.empty(), Optional.empty(), 7, 2);
        assertThat(pastTheEnd.content()).isEmpty();
        assertThat(pastTheEnd.totalElements()).isEqualTo(5);
    }

    @Test
    void anEmptyCatalogIsAnEmptyPageAndAsksInventoryNothing() {
        ProductListPage page = publicListing();

        assertThat(page.content()).isEmpty();
        assertThat(page.totalPages()).isZero();
        assertThat(inventory.calls("GET")).isEmpty();
    }
}
