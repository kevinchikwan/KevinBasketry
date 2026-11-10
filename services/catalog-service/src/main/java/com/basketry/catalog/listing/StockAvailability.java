package com.basketry.catalog.listing;

import com.basketry.catalog.inventory.InventoryClient;
import com.basketry.catalog.inventory.InventoryProperties;
import com.basketry.catalog.inventory.InventoryUnavailableException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The availability of a listing page's products, from one stock read in
 * inventory-service (FR-B6). Never cached, so a stock change shows on the next
 * listing. inventory-service not answering makes every product of the page
 * UNKNOWN, and listings then skip it for {@code skip-after-failure} instead of
 * waiting for a timeout each time, as the cache guard does with Redis.
 */
@Component
public class StockAvailability {

    private static final Logger log = LoggerFactory.getLogger(StockAvailability.class);

    private final InventoryClient inventory;
    private final long skipMillis;
    private final Clock clock;

    private volatile long skipUntil;
    private volatile boolean down;

    @Autowired
    public StockAvailability(InventoryClient inventory, InventoryProperties properties) {
        this(inventory, properties, Clock.systemUTC());
    }

    public StockAvailability(InventoryClient inventory, InventoryProperties properties, Clock clock) {
        this.inventory = inventory;
        this.skipMillis = properties.skipAfterFailure().toMillis();
        this.clock = clock;
    }

    /** At most {@link InventoryClient#MAX_IDS_PER_READ} products, the largest page. */
    public Map<Long, Availability> of(List<CachedProduct> products) {
        Map<Long, Availability> availability = new HashMap<>();
        if (products.isEmpty()) {
            return availability;
        }
        Map<Long, Long> stock = read(products);
        for (CachedProduct product : products) {
            availability.put(product.id(), availabilityOf(product, stock));
        }
        return availability;
    }

    private static Availability availabilityOf(CachedProduct product, Map<Long, Long> stock) {
        if (product.stockConflict()) {
            // The stock in inventory-service is another merchant's product's, never this one's.
            return Availability.OUT_OF_STOCK;
        }
        if (stock == null) {
            return Availability.UNKNOWN;
        }
        Long quantity = stock.get(product.id());
        // No stock row: a new product whose row the relay has not created yet.
        return quantity != null && quantity > 0 ? Availability.IN_STOCK : Availability.OUT_OF_STOCK;
    }

    /** Null if inventory-service was skipped or did not answer. */
    private Map<Long, Long> read(List<CachedProduct> products) {
        if (clock.millis() < skipUntil) {
            return null;
        }
        try {
            Map<Long, Long> stock = inventory.stockOf(products.stream().map(CachedProduct::id).toList());
            if (down) {
                down = false;
                log.info("inventory-service answers stock reads again");
            }
            return stock;
        } catch (InventoryUnavailableException e) {
            skipUntil = clock.millis() + skipMillis;
            if (!down) {
                down = true;
                log.warn("inventory-service did not answer a stock read ({}). Listings show availability UNKNOWN;"
                        + " tried again in {} ms", e.getMessage(), skipMillis);
            }
            return null;
        }
    }
}
