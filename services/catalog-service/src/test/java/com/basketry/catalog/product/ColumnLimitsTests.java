package com.basketry.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.listing.ListedProduct;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Row 13 of the Day 12 review: the largest values validation lets through fit
 * every column, on H2 and, in postgresTest, on PostgreSQL. Mutation: narrow a
 * column below its limit (for example name VARCHAR(99)), or raise a limit above
 * its column.
 */
class ColumnLimitsTests extends CatalogServiceTestBase {

    private static final long MAX_ID = Long.MAX_VALUE;

    @Test
    void theLargestValidProductIsStoredReadAndListedUnchanged() {
        String name = "n".repeat(ProductLimits.MAX_NAME_LENGTH);
        String description = "d".repeat(ProductLimits.MAX_DESCRIPTION_LENGTH);
        String category = "c".repeat(ProductLimits.MAX_CATEGORY_LENGTH);

        ProductView created = productService.create(
                new CreateProductCommand(MAX_ID, name, description, category, ProductLimits.MAX_PRICE));

        Product stored = products.findById(created.id()).orElseThrow();
        assertThat(stored.getMerchantId()).isEqualTo(MAX_ID);
        assertThat(stored.getName()).isEqualTo(name);
        assertThat(stored.getDescription()).isEqualTo(description);
        assertThat(stored.getCategory()).isEqualTo(category);
        assertThat(stored.getPrice()).isEqualTo(new BigDecimal("99999.99"));
        assertThat(stockRowRequestOf(created.id()).getMerchantId()).isEqualTo(MAX_ID);
        ListedProduct listed = listing.page(OptionalLong.of(MAX_ID), Optional.of(category), 0, 20)
                .content().getFirst();
        assertThat(listed.price()).isEqualTo(new BigDecimal("99999.99"));
        assertThat(listed.description()).isEqualTo(description);
    }

    /** UTF-16 code units are what the limit counts: 50 non-ASCII letters fit the category column. */
    @Test
    void nonAsciiTextAtTheLimitFits() {
        String category = "é".repeat(ProductLimits.MAX_CATEGORY_LENGTH);

        long id = productService.create(new CreateProductCommand(MERCHANT, "茶".repeat(100), "", category,
                new BigDecimal("1.00"))).id();

        assertThat(products.findById(id).orElseThrow().getCategory()).isEqualTo(category);
    }

    @Test
    void theLargestMerchantIdAndVersionFitTheStatusColumns() {
        merchantStatuses.apply(MAX_ID, false, MAX_ID);

        assertThat(merchantStatusRepository.findById(MAX_ID).orElseThrow().getVersion()).isEqualTo(MAX_ID);
    }

    /** The relay cuts last_error to 500 characters, and the column holds that much. */
    @Test
    void aLongErrorIsCutToItsColumn() {
        long id = givenProduct(MERCHANT, "Tea").id();

        stockRowRequests.recordFailure(id, "e".repeat(500));

        assertThat(stockRowRequestOf(id).getLastError()).hasSize(500);
    }
}
