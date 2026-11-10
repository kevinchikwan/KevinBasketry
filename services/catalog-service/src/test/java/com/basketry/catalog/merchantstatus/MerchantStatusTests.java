package com.basketry.catalog.merchantstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.catalog.support.CatalogServiceTestBase;
import org.junit.jupiter.api.Test;

/**
 * Row 3 of the Day 12 review: the local copy of a merchant's status, applied
 * by one conditional MERGE (DESIGN.md Section 5). The listener that calls this
 * comes on Day 13. Runs on H2 and, in postgresTest, on PostgreSQL, because the
 * MERGE is the one statement both must run alike.
 */
class MerchantStatusTests extends CatalogServiceTestBase {

    private MerchantStatus stored(long merchantId) {
        return merchantStatusRepository.findById(merchantId).orElseThrow();
    }

    /** Mutation: drop the NOT MATCHED branch (a merchant's first event would be lost). */
    @Test
    void theFirstEventOfAMerchantCreatesItsRow() {
        assertThat(merchantStatuses.apply(MERCHANT, false, 1)).isTrue();

        assertThat(stored(MERCHANT).isActive()).isFalse();
        assertThat(stored(MERCHANT).getVersion()).isEqualTo(1);
    }

    /**
     * Mutation: "t.version <= s.version" (a redelivered event would apply
     * again and invalidate the listings again).
     */
    @Test
    void aDuplicateIsIgnored() {
        OwnCache own = onOwnCache();
        own.merchantStatuses().apply(MERCHANT, false, 1);
        int bumps = own.fake().bumps();

        assertThat(own.merchantStatuses().apply(MERCHANT, false, 1)).isFalse();

        assertThat(own.fake().bumps()).as("no invalidation for an ignored event").isEqualTo(bumps);
    }

    /** Mutation: drop the version condition (a late, older event would undo a newer one). */
    @Test
    void anOlderEventIsIgnored() {
        merchantStatuses.apply(MERCHANT, false, 3);

        assertThat(merchantStatuses.apply(MERCHANT, true, 2)).isFalse();

        assertThat(stored(MERCHANT).isActive()).isFalse();
        assertThat(stored(MERCHANT).getVersion()).isEqualTo(3);
    }

    /** Gaps are not detected: an event carries the full state. Mutation: require version = stored + 1. */
    @Test
    void aGapIsAppliedAndTheMissingEventIsThenIgnored() {
        merchantStatuses.apply(MERCHANT, false, 1);

        assertThat(merchantStatuses.apply(MERCHANT, true, 3)).isTrue();
        assertThat(merchantStatuses.apply(MERCHANT, false, 2)).isFalse();

        assertThat(stored(MERCHANT).isActive()).isTrue();
        assertThat(stored(MERCHANT).getVersion()).isEqualTo(3);
    }

    /** Merchants never interfere. Mutation: a MERGE ON without the merchant id. */
    @Test
    void eachMerchantHasItsOwnRow() {
        merchantStatuses.apply(MERCHANT, false, 5);
        merchantStatuses.apply(OTHER_MERCHANT, false, 1);
        merchantStatuses.apply(OTHER_MERCHANT, true, 2);

        assertThat(stored(MERCHANT).isActive()).isFalse();
        assertThat(stored(MERCHANT).getVersion()).isEqualTo(5);
        assertThat(stored(OTHER_MERCHANT).isActive()).isTrue();
    }

    /** Events of merchants with no products are stored too: they may create products later. */
    @Test
    void aMerchantWithNoProductsKeepsItsStatusForLater() {
        merchantStatuses.apply(MERCHANT, false, 1);

        givenProduct(MERCHANT, "Tea");

        assertThat(publicListing().content()).isEmpty();
    }

    @Test
    void idsAndVersionsMustBePositive() {
        assertThatThrownBy(() -> merchantStatuses.apply(0, true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> merchantStatuses.apply(MERCHANT, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
