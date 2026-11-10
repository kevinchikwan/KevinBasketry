package com.basketry.catalog.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.cache.CacheKeys;
import com.basketry.catalog.product.ProductChanges;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Row 8 of the Day 12 review: the listing cache. A price changed in the
 * database behind the service's back (no invalidation) is how these tests see
 * whether a listing came from the cache.
 */
class ListingCacheTests extends CatalogServiceTestBase {

    private static ProductChanges price(String value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal(value)), Optional.empty());
    }

    private static BigDecimal priceIn(ProductListPage page, long productId) {
        return page.content().stream().filter(p -> p.id() == productId).findFirst().orElseThrow().price();
    }

    /** Mutation: never store a page (every listing would query the database). */
    @Test
    void aSecondListingIsServedFromTheCache() {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        publicListing();

        changePriceInTheDatabaseOnly(tea, "9.99");

        assertThat(priceIn(publicListing(), tea)).isEqualByComparingTo("2.50");
    }

    /** The price keeps its scale through the cache: 2.50, not 2.5. */
    @Test
    void aCachedPriceKeepsItsScale() {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        publicListing();

        assertThat(priceIn(publicListing(), tea)).isEqualTo(new BigDecimal("2.50"));
    }

    /** Mutation: drop the invalidation from create. */
    @Test
    void aNewProductShowsAtOnce() {
        givenProduct(MERCHANT, "Tea");
        publicListing();

        givenProduct(MERCHANT, "Coffee");

        assertThat(names(publicListing())).containsExactly("Tea", "Coffee");
    }

    /** Mutation: drop the invalidation from update (the "stale cache" mutation). */
    @Test
    void anUpdateShowsAtOnce() {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        publicListing();

        productService.update(MERCHANT, tea, price("3.00"));

        assertThat(priceIn(publicListing(), tea)).isEqualByComparingTo("3.00");
    }

    /** Mutation: bump only the "all" scope (a merchant-filtered listing would stay stale). */
    @Test
    void anUpdateShowsAtOnceInTheMerchantsOwnListingToo() {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        publicListingOf(MERCHANT);

        productService.update(MERCHANT, tea, price("3.00"));

        assertThat(priceIn(publicListingOf(MERCHANT), tea)).isEqualByComparingTo("3.00");
    }

    /** Mutation: drop the invalidation from MerchantStatusService. */
    @Test
    void aMerchantDeactivationShowsAtOnce() {
        givenProduct(MERCHANT, "Tea");
        publicListing();
        publicListingOf(MERCHANT);

        merchantStatuses.apply(MERCHANT, false, 1);

        assertThat(publicListing().content()).isEmpty();
        assertThat(publicListingOf(MERCHANT).content()).isEmpty();
    }

    /** Another merchant's change leaves this merchant's cached listing alone. */
    @Test
    void anotherMerchantsChangeKeepsThisMerchantsPagesCached() {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        publicListingOf(MERCHANT);
        changePriceInTheDatabaseOnly(tea, "9.99");

        givenProduct(OTHER_MERCHANT, "Coffee");

        assertThat(priceIn(publicListingOf(MERCHANT), tea)).isEqualByComparingTo("2.50");
    }

    /** Mutation: leave the category out of the page key (one category's page served for another). */
    @Test
    void differentCategoriesAreDifferentPages() {
        givenProduct(MERCHANT, "Tea");
        productService.create(new com.basketry.catalog.product.CreateProductCommand(MERCHANT, "Soap", "",
                "Household", new BigDecimal("1.00")));

        assertThat(names(listing.page(OptionalLong.empty(), Optional.of("Groceries"), 0, 20))).containsExactly("Tea");
        assertThat(names(listing.page(OptionalLong.empty(), Optional.of("Household"), 0, 20))).containsExactly("Soap");
        assertThat(names(publicListing())).containsExactly("Tea", "Soap");
    }

    /** Mutation: merge the availability before storing the page (stock would be cached too). */
    @Test
    void stockIsNeverCached() {
        long tea = givenProduct(MERCHANT, "Tea").id();
        inventory.givenStock(tea, MERCHANT, 0);
        assertThat(publicListing().content().getFirst().availability()).isEqualTo(Availability.OUT_OF_STOCK);

        inventory.givenStock(tea, MERCHANT, 5);

        assertThat(publicListing().content().getFirst().availability()).isEqualTo(Availability.IN_STOCK);
        assertThat(inventory.calls("GET")).hasSize(2);
    }

    /**
     * The race the version order closes: a writer commits and bumps between the
     * listing's version read and its database query. The page is then built
     * from the new data, or stored under the old version; either way the next
     * listing shows the new price. Mutation: query the database before reading
     * the version (the old page would be stored under the new version and
     * served until its TTL ends).
     */
    @Test
    void aCommitBetweenTheVersionReadAndTheQueryIsNeverServedStale() {
        OwnCache own = onOwnCache();
        long tea = own.products().create(command(MERCHANT, "Tea", "2.50")).id();
        own.fake().onNextVersionRead(() -> own.products().update(MERCHANT, tea, price("3.00")));

        own.publicListing();

        assertThat(priceIn(own.publicListing(), tea)).isEqualByComparingTo("3.00");
    }

    /**
     * Every bump happens after the commit: inside the bump, the change is
     * already visible to another connection and no transaction is open.
     * Mutation: invalidate inside the transaction, with ListingInvalidator's
     * check removed (a listing could read the new version and still query the
     * old data, then cache it under the new version).
     */
    @Test
    void listingsAreInvalidatedOnlyAfterTheCommit() {
        OwnCache own = onOwnCache();
        long tea = own.products().create(command(MERCHANT, "Tea", "2.50")).id();
        AtomicReference<String> seen = new AtomicReference<>();
        own.fake().onEveryBump(() -> seen.compareAndSet(null,
                TransactionSynchronizationManager.isActualTransactionActive() + " "
                        + jdbc.queryForObject("SELECT price FROM product WHERE id = ?", BigDecimal.class, tea)));

        own.products().update(MERCHANT, tea, price("3.00"));

        assertThat(seen.get()).isEqualTo("false 3.00");
    }

    /** Redis down: the listing comes from the database, correct, and nothing is stored. Mutation: no fallback. */
    @Test
    void withRedisDownListingsComeFromTheDatabase() {
        OwnCache own = onOwnCache();
        long tea = own.products().create(command(MERCHANT, "Tea", "2.50")).id();
        own.fake().setUnavailable(true);

        changePriceInTheDatabaseOnly(tea, "9.99");

        assertThat(priceIn(own.publicListing(), tea)).isEqualByComparingTo("9.99");
        own.fake().setUnavailable(false);
        assertThat(own.fake().puts()).isZero();
    }

    /**
     * A failed bump leaves the old pages reachable until their TTL ends: the
     * one documented way a listing can be stale, and never for longer.
     */
    @Test
    void aMissedInvalidationIsStaleForAtMostTheTtl() {
        MutableClock time = new MutableClock();
        OwnCache own = onOwnCache(time);
        own.fake().useClock(time);
        long tea = own.products().create(command(MERCHANT, "Tea", "2.50")).id();
        own.publicListing();

        own.fake().setUnavailable(true);
        own.products().update(MERCHANT, tea, price("3.00"));
        own.fake().setUnavailable(false);
        time.advance(cacheProperties.ttl().minusSeconds(1));
        assertThat(priceIn(own.publicListing(), tea)).as("within the TTL").isEqualByComparingTo("2.50");

        time.advance(Duration.ofSeconds(1));
        assertThat(priceIn(own.publicListing(), tea)).as("after the TTL").isEqualByComparingTo("3.00");
    }

    /** A page that cannot be read is a miss and is overwritten. Mutation: let the read failure propagate. */
    @Test
    void anUnreadableCachedPageIsAMiss() {
        long tea = givenProduct(MERCHANT, "Tea", "2.50").id();
        cache.put(CacheKeys.pageKey(CacheKeys.ALL, cache.version(CacheKeys.ALL), Optional.empty(), 0, 20),
                "{not json", Duration.ofSeconds(60));

        assertThat(priceIn(publicListing(), tea)).isEqualByComparingTo("2.50");
        changePriceInTheDatabaseOnly(tea, "9.99");
        assertThat(priceIn(publicListing(), tea)).as("the page was stored again").isEqualByComparingTo("2.50");
    }
}
