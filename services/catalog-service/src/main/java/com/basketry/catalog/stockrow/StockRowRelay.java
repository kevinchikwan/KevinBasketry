package com.basketry.catalog.stockrow;

import com.basketry.catalog.cache.ListingInvalidator;
import com.basketry.catalog.inventory.InventoryClient;
import com.basketry.catalog.inventory.StockRowAnswer;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Creates the stock rows of new products in inventory-service (DESIGN.md
 * Section 2, catalog-service): takes PENDING requests in product order, calls
 * POST /internal/stock for each, and records the answer in its own short
 * transaction. No transaction is open during a call. Not
 * {@code @Transactional}: one transaction around a pass would roll back the
 * marks of requests already done when a later one fails.
 *
 * <ul>
 *   <li>DONE (201 or 200): CREATED.</li>
 *   <li>CONFLICT (409): CONFLICT, final, an ERROR, and the merchant's listings
 *       are invalidated so the product shows as out of stock at once. The pass
 *       goes on: the next request is unrelated.</li>
 *   <li>REJECTED (another 4xx): REJECTED, final, an ERROR. The pass goes on.</li>
 *   <li>RETRY (401, 5xx, timeout, no connection): the request stays PENDING
 *       and the pass stops, because the next request would fail the same way.
 *       The scheduler backs off.</li>
 * </ul>
 *
 * Requests need no order between them, so there is no rule that a later one
 * must wait for an earlier one; stopping on RETRY only saves calls. With two
 * instances a request may be sent twice, which the endpoint answers 200
 * ALREADY_EXISTS, and only the first mark changes the row.
 */
@Service
public class StockRowRelay {

    private static final Logger log = LoggerFactory.getLogger(StockRowRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final StockRowRequestRepository requests;
    private final InventoryClient inventory;
    private final ListingInvalidator invalidator;
    private final Clock clock;
    private final int batchSize;

    public StockRowRelay(StockRowRequestRepository requests, InventoryClient inventory,
                         ListingInvalidator invalidator, Clock clock, StockRowProperties properties) {
        this.requests = requests;
        this.inventory = inventory;
        this.invalidator = invalidator;
        this.clock = clock;
        this.batchSize = properties.relay().batchSize();
    }

    /** One pass. False if a request must be tried again later; the caller decides when. */
    public boolean createPending() {
        List<StockRowRequest> batch =
                requests.findByStatusOrderByProductIdAsc(StockRowStatus.PENDING, Limit.of(batchSize));
        for (StockRowRequest request : batch) {
            StockRowAnswer answer = inventory.createStockRow(request.getProductId(), request.getMerchantId());
            switch (answer.kind()) {
                case DONE -> requests.complete(request.getProductId(), StockRowStatus.CREATED, null, clock.instant());
                case CONFLICT -> {
                    log.error("The stock row of product {} belongs to another merchant in inventory-service, not to"
                            + " merchant {}. The product stays out of stock (DESIGN.md Section 2, catalog-service).",
                            request.getProductId(), request.getMerchantId());
                    if (requests.complete(request.getProductId(), StockRowStatus.CONFLICT, answer.detail(),
                            clock.instant()) == 1) {
                        invalidator.merchantChanged(request.getMerchantId());
                    }
                }
                case REJECTED -> {
                    log.error("inventory-service rejected the stock row of product {}: {}",
                            request.getProductId(), answer.detail());
                    requests.complete(request.getProductId(), StockRowStatus.REJECTED, truncated(answer.detail()),
                            clock.instant());
                }
                case RETRY -> {
                    log.warn("Could not create the stock row of product {}: {}", request.getProductId(),
                            answer.detail());
                    requests.recordFailure(request.getProductId(), truncated(answer.detail()));
                    return false;
                }
            }
        }
        return true;
    }

    private static String truncated(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
