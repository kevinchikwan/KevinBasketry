package com.basketry.catalog.listing;

import com.basketry.catalog.cache.CacheKeys;
import com.basketry.catalog.cache.ProductListCacheGuard;
import com.basketry.catalog.inventory.InventoryClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public listing, GET /products (FR-B3, FR-B6; DESIGN.md Section 2,
 * catalog-service): cache-aside in Redis, then the availability from
 * inventory-service, which is never cached.
 *
 * <p><b>The order is what keeps the cache correct.</b> The scope's version is
 * read first, then the page is looked up under it, and on a miss the database
 * is queried and the page stored under that same version. Writers bump the
 * version only after their commit ({@code cache.ListingInvalidator}). So a page
 * built from data older than a commit always sits under a version older than
 * the bump after it, and no later listing reads it. Reading the version after
 * the query would break this: a commit and bump between the two would store
 * the old data under the new version.
 *
 * <p>Redis unavailable: no version, so the page comes from the database and is
 * not stored. Never called inside a transaction (no caller opens one), so no
 * connection is held during the Redis and HTTP calls.
 */
@Service
public class ProductListing {

    private static final Logger log = LoggerFactory.getLogger(ProductListing.class);

    private final ProductQueries queries;
    private final ProductListCacheGuard cache;
    private final StockAvailability stock;
    private final JsonMapper json;

    public ProductListing(ProductQueries queries, ProductListCacheGuard cache, StockAvailability stock,
                          JsonMapper json) {
        this.queries = queries;
        this.cache = cache;
        this.stock = stock;
        this.json = json;
    }

    /**
     * @param page from 0
     * @param size 1 to {@link InventoryClient#MAX_IDS_PER_READ}; the REST layer refuses anything else with a 400
     */
    public ProductListPage page(OptionalLong merchantId, Optional<String> category, int page, int size) {
        if (page < 0 || size < 1 || size > InventoryClient.MAX_IDS_PER_READ) {
            throw new IllegalArgumentException("page must be at least 0 and size 1 to 100");
        }
        CachedPage cached = cachedOrQueried(merchantId, category, page, size);
        Map<Long, Availability> availability = stock.of(cached.content());
        List<ListedProduct> content = cached.content().stream()
                .map(product -> ListedProduct.of(product, availability.get(product.id())))
                .toList();
        return ProductListPage.of(content, page, size, cached.totalElements());
    }

    /**
     * GET /products/{id}: one product with its availability, if the listing
     * would show it; empty (a 404) for a product that is inactive, of an
     * inactive merchant, or missing. Not cached: one indexed read.
     */
    public Optional<ListedProduct> product(long id) {
        return queries.publicProduct(id).map(product ->
                ListedProduct.of(product, stock.of(List.of(product)).get(product.id())));
    }

    private CachedPage cachedOrQueried(OptionalLong merchantId, Optional<String> category, int page, int size) {
        OptionalLong version = cache.version(CacheKeys.scopeOf(merchantId));
        if (version.isEmpty()) {
            return queries.publicPage(merchantId, category, page, size);
        }
        String key = CacheKeys.pageKey(CacheKeys.scopeOf(merchantId), version.getAsLong(), category, page, size);
        Optional<CachedPage> hit = cache.get(key).flatMap(this::read);
        if (hit.isPresent()) {
            return hit.get();
        }
        CachedPage queried = queries.publicPage(merchantId, category, page, size);
        cache.put(key, json.writeValueAsString(queried));
        return queried;
    }

    /** Empty for a page that cannot be read, which then counts as a miss and is overwritten. */
    private Optional<CachedPage> read(String page) {
        try {
            return Optional.of(json.readValue(page, CachedPage.class));
        } catch (JacksonException unreadable) {
            log.warn("Ignored an unreadable cached listing page: {}", unreadable.getOriginalMessage());
            return Optional.empty();
        }
    }
}
