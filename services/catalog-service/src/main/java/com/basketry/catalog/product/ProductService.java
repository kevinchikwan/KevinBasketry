package com.basketry.catalog.product;

import com.basketry.catalog.cache.ListingInvalidator;
import com.basketry.catalog.stockrow.StockRowRequest;
import com.basketry.catalog.stockrow.StockRowRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates and changes products (FR-M2). Each write is one transaction, and the
 * listings are invalidated only after it commits ({@link ListingInvalidator}),
 * so the methods are not {@code @Transactional}: they run the transaction
 * themselves and invalidate when it is over.
 */
@Service
public class ProductService {

    private final ProductRepository products;
    private final StockRowRequestRepository stockRowRequests;
    private final ListingInvalidator invalidator;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProductService(ProductRepository products, StockRowRequestRepository stockRowRequests,
                          ListingInvalidator invalidator, PlatformTransactionManager transactionManager,
                          Clock clock) {
        this.products = products;
        this.stockRowRequests = stockRowRequests;
        this.invalidator = invalidator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * The product and its stock-row request in one transaction (the outbox,
     * DESIGN.md Section 2): either both exist or neither does. The relay
     * creates the stock row in inventory-service later; until then the product
     * reads as out of stock.
     */
    public ProductView create(CreateProductCommand command) {
        ProductView created = transaction.execute(status -> {
            Instant now = now();
            Product product = products.saveAndFlush(new Product(command, now));
            stockRowRequests.save(new StockRowRequest(product.getId(), product.getMerchantId(), now));
            return product.view();
        });
        invalidator.merchantChanged(created.merchantId());
        return created;
    }

    /**
     * Changes the owner's product under its row lock. A product that does not
     * exist and one of another merchant get the same {@link ProductNotOwnedException}.
     * A change that leaves every value as it was still succeeds.
     *
     * @throws ProductBusyException if another request holds the row lock past the timeout
     */
    public ProductView update(long merchantId, long productId, ProductChanges changes) {
        ProductView updated = transaction.execute(status -> {
            Product product;
            try {
                product = products.findByIdForUpdate(productId)
                        .filter(found -> found.getMerchantId() == merchantId)
                        .orElseThrow(ProductNotOwnedException::new);
            } catch (PessimisticLockingFailureException lockTimedOut) {
                throw new ProductBusyException(lockTimedOut);
            }
            changes.applyTo(product);
            product.touch(now());
            return product.view();
        });
        invalidator.merchantChanged(updated.merchantId());
        return updated;
    }

    /** At the precision the columns keep, so an answer shows exactly what a later read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}