package com.basketry.catalog.listing;

import java.util.List;

/**
 * One listing page as the cache stores it, JSON under a page key
 * (cache.CacheKeys). A change of this shape needs a new key prefix there.
 */
public record CachedPage(List<CachedProduct> content, long totalElements) {
}
