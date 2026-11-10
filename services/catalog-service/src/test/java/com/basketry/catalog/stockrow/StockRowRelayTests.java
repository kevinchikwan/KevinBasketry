package com.basketry.catalog.stockrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.listing.Availability;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Row 10 of the Day 12 review: the stock-row relay, against the fake inventory-service over real HTTP. */
@ExtendWith(OutputCaptureExtension.class)
class StockRowRelayTests extends CatalogServiceTestBase {

    @Autowired
    private StockRowRelay relay;

    @Autowired
    private StockRowProperties properties;

    /** Mutation: never mark CREATED (every pass would call again for every product, forever). */
    @Test
    void aNewProductGetsItsStockRowOnceAndIsMarkedCreated() {
        long tea = givenProduct(MERCHANT, "Tea").id();

        assertThat(relay.createPending()).isTrue();
        assertThat(relay.createPending()).isTrue();

        assertThat(inventory.ownerOfRows()).containsExactlyEntriesOf(Map.of(tea, MERCHANT));
        assertThat(inventory.calls("POST")).hasSize(1);
        StockRowRequest request = stockRowRequestOf(tea);
        assertThat(request.getStatus()).isEqualTo(StockRowStatus.CREATED);
        assertThat(request.getCompletedAt()).isNotNull();
    }

    /** After the row exists, the product's stock shows. */
    @Test
    void onceCreatedTheProductIsOutOfStockUntilTheMerchantAddsSome() {
        long tea = givenProduct(MERCHANT, "Tea").id();
        relay.createPending();

        assertThat(publicListing().content().getFirst().availability()).isEqualTo(Availability.OUT_OF_STOCK);
        inventory.givenStock(tea, MERCHANT, 3);
        assertThat(publicListing().content().getFirst().availability()).isEqualTo(Availability.IN_STOCK);
    }

    /**
     * A row of another merchant: CONFLICT, final, an ERROR, and the product
     * shows as out of stock at once although that row has stock. The pass goes
     * on to the next product. Mutations: stop the pass on a conflict; leave the
     * request PENDING (called forever); skip the invalidation (the cached page
     * would show the other merchant's stock until the TTL ends).
     */
    @Test
    void aConflictIsFinalOutOfStockAndDoesNotHoldUpTheNextProduct(CapturedOutput output) {
        long tea = givenProduct(MERCHANT, "Tea").id();
        long coffee = givenProduct(MERCHANT, "Coffee").id();
        inventory.givenStock(tea, OTHER_MERCHANT, 50);
        assertThat(publicListing().content().getFirst().availability()).as("before").isEqualTo(Availability.IN_STOCK);

        assertThat(relay.createPending()).isTrue();
        relay.createPending();

        assertThat(stockRowRequestOf(tea).getStatus()).isEqualTo(StockRowStatus.CONFLICT);
        assertThat(stockRowRequestOf(tea).getLastError()).contains("PRODUCT_OWNED_BY_OTHER_MERCHANT");
        assertThat(stockRowRequestOf(coffee).getStatus()).isEqualTo(StockRowStatus.CREATED);
        assertThat(inventory.calls("POST")).hasSize(2);
        assertThat(publicListing().content().getFirst().availability()).as("after").isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(output).contains("ERROR").contains("The stock row of product " + tea + " belongs to another merchant");
    }

    /** Another 4xx: REJECTED, final, an ERROR, and the pass goes on. */
    @Test
    void aRejectedRequestIsFinal(CapturedOutput output) {
        long tea = givenProduct(MERCHANT, "Tea").id();
        inventory.answerCreatesWith(400);

        assertThat(relay.createPending()).isTrue();
        relay.createPending();

        assertThat(stockRowRequestOf(tea).getStatus()).isEqualTo(StockRowStatus.REJECTED);
        assertThat(inventory.calls("POST")).hasSize(1);
        assertThat(output).contains("ERROR").contains("inventory-service rejected the stock row of product " + tea);
    }

    /**
     * 503: the request stays PENDING with the attempt recorded, the pass stops
     * (the next product is not called), and the next pass completes both.
     * Mutation: mark a retryable failure final (the product would never get a
     * stock row once inventory-service is back).
     */
    @Test
    void aRetryableFailureStaysPendingAndStopsThePass() {
        long tea = givenProduct(MERCHANT, "Tea").id();
        long coffee = givenProduct(MERCHANT, "Coffee").id();
        inventory.answerCreatesWith(503);

        assertThat(relay.createPending()).isFalse();

        assertThat(inventory.calls("POST")).hasSize(1);
        StockRowRequest request = stockRowRequestOf(tea);
        assertThat(request.getStatus()).isEqualTo(StockRowStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getLastError()).isEqualTo("503 from inventory-service");

        inventory.answerCreatesWith(null);
        assertThat(relay.createPending()).isTrue();
        assertThat(inventory.ownerOfRows()).containsOnlyKeys(tea, coffee);
        assertThat(stockRowRequestOf(tea).getStatus()).isEqualTo(StockRowStatus.CREATED);
    }

    /** A pass handles at most batch-size requests, in product order. */
    @Test
    void aPassHandlesTheOldestBatchFirst() {
        int batch = properties.relay().batchSize();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i <= batch; i++) {
            ids.add(givenProduct(MERCHANT, "P" + i).id());
        }

        relay.createPending();

        assertThat(inventory.ownerOfRows()).hasSize(batch).doesNotContainKey(ids.getLast());
        relay.createPending();
        assertThat(inventory.ownerOfRows()).hasSize(batch + 1);
    }

    /**
     * A final status never changes, even if another instance's mark comes
     * later. Mutation: drop "status = PENDING" from the mark.
     */
    @Test
    void aFinalStatusNeverChanges() {
        long tea = givenProduct(MERCHANT, "Tea").id();
        inventory.givenStock(tea, OTHER_MERCHANT, 0);
        relay.createPending();

        assertThat(stockRowRequests.complete(tea, StockRowStatus.CREATED, null, clock.instant())).isZero();

        assertThat(stockRowRequestOf(tea).getStatus()).isEqualTo(StockRowStatus.CONFLICT);
    }
}
