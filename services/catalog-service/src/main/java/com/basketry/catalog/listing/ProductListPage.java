package com.basketry.catalog.listing;

import java.util.List;

/** A page of the public listing, in the paging shape of GET /accounts (DESIGN.md Section 3). */
public record ProductListPage(List<ListedProduct> content, int page, int size, long totalElements,
                              int totalPages) {

    static ProductListPage of(List<ListedProduct> content, int page, int size, long totalElements) {
        return new ProductListPage(content, page, size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }
}
