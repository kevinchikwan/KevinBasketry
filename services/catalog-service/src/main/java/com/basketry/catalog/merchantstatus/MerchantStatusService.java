package com.basketry.catalog.merchantstatus;

import com.basketry.catalog.cache.ListingInvalidator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies a merchant's status to the local copy and, if it changed anything,
 * invalidates the listings that can show the merchant's products. The
 * merchant.status.changed listener parses each record and calls this, outside
 * any transaction; tests call it directly unless they turn the listener on.
 *
 * <p>Not transactional: the MERGE commits on its own, and the invalidation
 * must come after the commit ({@link ListingInvalidator}). A crash in between
 * leaves listings stale for at most the cache TTL; the redelivered event is
 * then ignored by version, which is fine because the TTL ends anyway.
 */
@Service
public class MerchantStatusService {

    private static final Logger log = LoggerFactory.getLogger(MerchantStatusService.class);

    private final MerchantStatusRepository statuses;
    private final ListingInvalidator invalidator;
    private final Clock clock;

    public MerchantStatusService(MerchantStatusRepository statuses, ListingInvalidator invalidator, Clock clock) {
        this.statuses = statuses;
        this.invalidator = invalidator;
        this.clock = clock;
    }

    /** @return true if the event was newer than what was stored, and was applied */
    public boolean apply(long merchantId, boolean active, long version) {
        if (merchantId <= 0 || version <= 0) {
            throw new IllegalArgumentException("merchantId and version must be positive");
        }
        if (statuses.apply(merchantId, active, version, clock.instant()) == 0) {
            log.debug("Ignored status event of merchant {}: version {} is not newer", merchantId, version);
            return false;
        }
        log.info("Merchant {} is now active={} (version {})", merchantId, active, version);
        invalidator.merchantChanged(merchantId);
        return true;
    }
}
