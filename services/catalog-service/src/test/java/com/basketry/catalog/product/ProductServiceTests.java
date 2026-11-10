package com.basketry.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.catalog.cache.ListingInvalidator;
import com.basketry.catalog.cache.ProductListCacheGuard;
import com.basketry.catalog.stockrow.StockRowRequest;
import com.basketry.catalog.stockrow.StockRowRequestRepository;
import com.basketry.catalog.stockrow.StockRowStatus;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Rows 2 and 10 of the Day 12 review: creating and changing products, and the stock-row outbox. */
class ProductServiceTests extends CatalogServiceTestBase {

    private static ProductChanges price(String value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal(value)), Optional.empty());
    }

    private static ProductChanges active(boolean value) {
        return new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(value));
    }

    /** Mutation: drop the stock-row request from create (no stock row would ever be made). */
    @Test
    void aNewProductIsActiveAndHasAPendingStockRowRequestForItsMerchant() {
        ProductView created = givenProduct(MERCHANT, "Tea", "2.50");

        assertThat(created.active()).isTrue();
        assertThat(created.price()).isEqualTo(new BigDecimal("2.50"));
        assertThat(created.createdAt()).isEqualTo(created.updatedAt());
        StockRowRequest request = stockRowRequestOf(created.id());
        assertThat(request.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(request.getStatus()).isEqualTo(StockRowStatus.PENDING);
        assertThat(request.getCompletedAt()).isNull();
    }

    /**
     * The outbox's atomicity: the stock-row request cannot be saved, so the
     * product is not created either. Mutation: save the product in a
     * transaction of its own (a product would exist with no request, and never
     * get a stock row).
     */
    @Test
    void aProductIsNeverCreatedWithoutItsStockRowRequest() {
        ProductService failingOutbox = new ProductService(products, stockRowRequestsFailingOnSave(),
                new ListingInvalidator(new ProductListCacheGuard(cache, cacheProperties)), transactionManager, clock);

        assertThatThrownBy(() -> failingOutbox.create(command(MERCHANT, "Tea", "2.50")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(products.count()).isZero();
        assertThat(stockRowRequests.count()).isZero();
    }

    @Test
    void anUpdateChangesOnlyTheGivenFields() {
        ProductView created = givenProduct(MERCHANT, "Tea", "2.50");

        ProductView updated = productService.update(MERCHANT, created.id(), price("3.00"));

        assertThat(updated.price()).isEqualTo(new BigDecimal("3.00"));
        assertThat(updated.name()).isEqualTo("Tea");
        assertThat(updated.description()).isEqualTo("A description");
        assertThat(updated.active()).isTrue();
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());
        assertThat(products.findById(created.id()).orElseThrow().getPrice()).isEqualByComparingTo("3.00");
    }

    @Test
    void deactivatingAndReactivatingIsAnUpdateOfActive() {
        ProductView created = givenProduct(MERCHANT, "Tea");

        assertThat(productService.update(MERCHANT, created.id(), active(false)).active()).isFalse();
        assertThat(productService.update(MERCHANT, created.id(), active(true)).active()).isTrue();
    }

    /**
     * FR-M3: another merchant's product and a missing one get the same answer,
     * and nothing changes. Mutation: drop the owner check.
     */
    @Test
    void anotherMerchantsProductAndAMissingOneAreRefusedAlike() {
        ProductView created = givenProduct(MERCHANT, "Tea", "2.50");

        assertThatThrownBy(() -> productService.update(OTHER_MERCHANT, created.id(), price("0.01")))
                .isInstanceOf(ProductNotOwnedException.class);
        assertThatThrownBy(() -> productService.update(MERCHANT, created.id() + 1000, price("0.01")))
                .isInstanceOf(ProductNotOwnedException.class)
                .hasMessage(new ProductNotOwnedException().getMessage());

        assertThat(products.findById(created.id()).orElseThrow().getPrice()).isEqualByComparingTo("2.50");
    }

    /** Ids come from the sequence one by one, in creation order. */
    @Test
    void idsFollowCreationOrder() {
        long first = givenProduct(MERCHANT, "Tea").id();
        long second = givenProduct(OTHER_MERCHANT, "Coffee").id();

        assertThat(second).isEqualTo(first + 1);
    }

    /** The real repository, except that every save fails as a constraint violation would. */
    private StockRowRequestRepository stockRowRequestsFailingOnSave() {
        return (StockRowRequestRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {StockRowRequestRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        throw new DataIntegrityViolationException("simulated failure of the outbox insert");
                    }
                    try {
                        return method.invoke(stockRowRequests, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
