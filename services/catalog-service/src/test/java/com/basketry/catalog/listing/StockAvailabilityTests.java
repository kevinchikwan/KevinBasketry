package com.basketry.catalog.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.config.InternalApiKey;
import com.basketry.catalog.inventory.InventoryClient;
import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Row 7 of the Day 12 review: availability from inventory-service, against the
 * fake inventory-service over real HTTP. Tests that make it fail use their own
 * StockAvailability, so the shared one never remembers a failure.
 */
class StockAvailabilityTests extends CatalogServiceTestBase {

    private final MutableClock time = new MutableClock();

    private StockAvailability own() {
        return new StockAvailability(inventoryClient, inventoryProperties, time);
    }

    private static CachedProduct product(long id, boolean stockConflict) {
        return new CachedProduct(id, MERCHANT, "P" + id, "", "Groceries", "1.00", stockConflict);
    }

    /** Mutation: a product with no stock row counted as IN_STOCK or UNKNOWN. */
    @Test
    void stockDecidesAndAProductWithNoStockRowIsOutOfStock() {
        inventory.givenStock(1, MERCHANT, 5);
        inventory.givenStock(2, MERCHANT, 0);

        Map<Long, Availability> availability = stockAvailability.of(
                List.of(product(1, false), product(2, false), product(3, false)));

        assertThat(availability).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, Availability.IN_STOCK, 2L, Availability.OUT_OF_STOCK, 3L, Availability.OUT_OF_STOCK));
        assertThat(inventory.calls("GET")).singleElement()
                .satisfies(call -> assertThat(call.uri()).isEqualTo("/internal/stock?productIds=1,2,3"))
                .satisfies(call -> assertThat(call.apiKey()).isEqualTo(API_KEY));
    }

    /**
     * The stock row belongs to another merchant: its stock is not this
     * product's. Mutation: ignore stockConflict (another merchant's stock would
     * make this product look buyable).
     */
    @Test
    void aStockRowConflictIsAlwaysOutOfStock() {
        inventory.givenStock(1, OTHER_MERCHANT, 50);

        assertThat(stockAvailability.of(List.of(product(1, true)))).containsEntry(1L, Availability.OUT_OF_STOCK);
    }

    /** Mutation: inventory-service down read as OUT_OF_STOCK (the whole site would look sold out). */
    @Test
    void anErrorFromInventoryMakesTheWholePageUnknownButAConflictStaysOutOfStock() {
        inventory.answerReadsWith(503);

        assertThat(own().of(List.of(product(1, false), product(2, true))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, Availability.UNKNOWN, 2L, Availability.OUT_OF_STOCK));
    }

    /** The read timeout (1 s) bounds the extra latency of a listing. */
    @Test
    void aSlowInventoryIsUnknownAfterTheReadTimeout() {
        inventory.givenStock(1, MERCHANT, 5);
        inventory.answerAfter(Duration.ofSeconds(3));
        long started = System.nanoTime();

        assertThat(own().of(List.of(product(1, false)))).containsEntry(1L, Availability.UNKNOWN);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
    }

    /** Mutation: no skip window (every listing would wait for a timeout while inventory-service is down). */
    @Test
    void afterAFailureInventoryIsSkippedForTheWindowThenAskedAgain() {
        StockAvailability availability = own();
        inventory.answerReadsWith(500);
        availability.of(List.of(product(1, false)));
        inventory.answerReadsWith(null);
        inventory.givenStock(1, MERCHANT, 5);

        time.advance(inventoryProperties.skipAfterFailure().minusSeconds(1));
        assertThat(availability.of(List.of(product(1, false)))).containsEntry(1L, Availability.UNKNOWN);
        assertThat(inventory.calls("GET")).hasSize(1);

        time.advance(Duration.ofSeconds(1));
        assertThat(availability.of(List.of(product(1, false)))).containsEntry(1L, Availability.IN_STOCK);
    }

    /**
     * No key configured: the header is left out and inventory-service answers
     * 401, so listings show UNKNOWN (services/README.md). Mutation: send an
     * empty header, or fail at startup.
     */
    @Test
    void withoutAKeyInventoryRefusesAndAvailabilityIsUnknown() {
        RestClient restClient = RestClient.builder().baseUrl(inventory.baseUrl()).build();
        InventoryClient keyless = new InventoryClient(restClient, new InternalApiKey(""));
        inventory.givenStock(1, MERCHANT, 5);

        assertThat(new StockAvailability(keyless, inventoryProperties, time).of(List.of(product(1, false))))
                .containsEntry(1L, Availability.UNKNOWN);
        assertThat(inventory.calls("GET")).singleElement().satisfies(call -> assertThat(call.apiKey()).isNull());
    }
}
