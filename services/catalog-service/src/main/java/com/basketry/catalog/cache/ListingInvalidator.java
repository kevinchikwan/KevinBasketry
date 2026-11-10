package com.basketry.catalog.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Makes every cached listing that can show a merchant's products unreachable:
 * bumps the {@code all} scope and the merchant's own. Called for a product
 * create or update, a merchant status change and a stock-row conflict.
 *
 * <p><b>Only after the commit.</b> A listing reads its scope's version before
 * it queries the database, so a page built from data older than a commit is
 * always stored under a version older than the bump that follows the commit,
 * and nobody reads it. Bumping before the commit would let a listing read the
 * new version and still query the old data. So this refuses to run inside a
 * transaction.
 */
@Component
public class ListingInvalidator {

    private static final Logger log = LoggerFactory.getLogger(ListingInvalidator.class);

    private final ProductListCacheGuard cache;

    public ListingInvalidator(ProductListCacheGuard cache) {
        this.cache = cache;
    }

    public void merchantChanged(long merchantId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Listings are invalidated only after the commit");
        }
        boolean all = cache.bump(CacheKeys.ALL);
        boolean merchant = cache.bump(CacheKeys.merchantScope(merchantId));
        if (!all || !merchant) {
            log.warn("Could not invalidate the cached listings of merchant {}: they may be stale until their TTL"
                    + " ends", merchantId);
        }
    }
}
