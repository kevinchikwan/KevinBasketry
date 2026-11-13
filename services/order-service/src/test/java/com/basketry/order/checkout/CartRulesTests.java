package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.CheckoutRefusedException.RefusedLine;
import com.basketry.order.downstream.CatalogClient.CatalogProduct;
import com.basketry.order.downstream.CatalogClient.StockRow;
import com.basketry.order.downstream.MerchantClient.MerchantStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The cart rules (DESIGN.md Section 2, order-service, checkout step 5). */
class CartRulesTests {

    private static final long MERCHANT = 3;
    private static final Map<Long, MerchantStatus> MERCHANT_ACTIVE =
            Map.of(MERCHANT, new MerchantStatus(MERCHANT, true, new BigDecimal("5.00")));

    private static CatalogProduct product(long id, boolean active, boolean merchantActive, StockRow row) {
        return new CatalogProduct(id, MERCHANT, "Tea", new BigDecimal("2.50"), active, merchantActive, row);
    }

    private static List<RefusedLine> refused(Map<Long, CatalogProduct> products, Map<Long, MerchantStatus> merchants,
                                             OptionalLong buyerMerchant, long... productIds) {
        List<CheckoutLine> lines = java.util.Arrays.stream(productIds).mapToObj(id -> new CheckoutLine(id, 1L)).toList();
        return CartRules.refused(lines, products, merchants, buyerMerchant);
    }

    /** A PENDING stock row is not refused: its reservation decides. Mutation: refuse PENDING too. */
    @ParameterizedTest
    @EnumSource(value = StockRow.class, names = {"CREATED", "PENDING"})
    void aPurchasableLineIsNotRefused(StockRow row) {
        assertThat(refused(Map.of(1L, product(1, true, true, row)), MERCHANT_ACTIVE, OptionalLong.empty(), 1))
                .isEmpty();
    }

    /** Mutation: only CONFLICT refused (REJECTED let through). */
    @ParameterizedTest
    @EnumSource(value = StockRow.class, names = {"CONFLICT", "REJECTED"})
    void aConflictOrRejectedStockRowIsRefused(StockRow row) {
        assertThat(refused(Map.of(1L, product(1, true, true, row)), MERCHANT_ACTIVE, OptionalLong.empty(), 1))
                .containsExactly(new RefusedLine(1, RefusalReason.STOCK_ROW_UNAVAILABLE));
    }

    /** Mutation: an unknown product skipped instead of refused. */
    @Test
    void anUnknownProductIsNotFound() {
        assertThat(refused(Map.of(), Map.of(), OptionalLong.empty(), 9))
                .containsExactly(new RefusedLine(9, RefusalReason.NOT_FOUND));
    }

    @Test
    void anInactiveProductIsRefused() {
        assertThat(refused(Map.of(1L, product(1, false, true, StockRow.CREATED)), MERCHANT_ACTIVE,
                OptionalLong.empty(), 1)).containsExactly(new RefusedLine(1, RefusalReason.INACTIVE));
    }

    /**
     * Either flag false is enough, and a merchant merchant-service does not know
     * is inactive. Mutations: merchant-service's flag ignored; catalog's flag
     * ignored; an unknown merchant taken as active.
     */
    @Test
    void eitherServiceSayingInactiveOrAnUnknownMerchantIsMerchantInactive() {
        Map<Long, CatalogProduct> catalogSaysActive = Map.of(1L, product(1, true, true, StockRow.CREATED));
        Map<Long, CatalogProduct> catalogSaysInactive = Map.of(1L, product(1, true, false, StockRow.CREATED));
        Map<Long, MerchantStatus> merchantServiceSaysInactive =
                Map.of(MERCHANT, new MerchantStatus(MERCHANT, false, new BigDecimal("5.00")));
        RefusedLine merchantInactive = new RefusedLine(1, RefusalReason.MERCHANT_INACTIVE);

        assertThat(refused(catalogSaysActive, merchantServiceSaysInactive, OptionalLong.empty(), 1))
                .containsExactly(merchantInactive);
        assertThat(refused(catalogSaysInactive, MERCHANT_ACTIVE, OptionalLong.empty(), 1))
                .containsExactly(merchantInactive);
        assertThat(refused(catalogSaysActive, Map.of(), OptionalLong.empty(), 1)).containsExactly(merchantInactive);
    }

    /**
     * Only the buyer's own merchant's products. Mutations: the OWN_PRODUCT check
     * removed; compared with another id.
     */
    @Test
    void aMerchantCannotBuyItsOwnProducts() {
        Map<Long, CatalogProduct> products = Map.of(1L, product(1, true, true, StockRow.CREATED));

        assertThat(refused(products, MERCHANT_ACTIVE, OptionalLong.of(MERCHANT), 1))
                .containsExactly(new RefusedLine(1, RefusalReason.OWN_PRODUCT));
        assertThat(refused(products, MERCHANT_ACTIVE, OptionalLong.of(MERCHANT + 1), 1)).isEmpty();
    }

    /**
     * The first reason that applies, in DESIGN's order: a product of the buyer's
     * own, inactive merchant with a bad stock row is OWN_PRODUCT; an inactive
     * product of an inactive merchant is INACTIVE; and so on. Mutation: the
     * INACTIVE and MERCHANT_INACTIVE checks swapped.
     */
    @Test
    void eachLineGetsTheFirstReasonThatApplies() {
        Map<Long, MerchantStatus> inactiveMerchant =
                Map.of(MERCHANT, new MerchantStatus(MERCHANT, false, new BigDecimal("5.00")));

        assertThat(refused(Map.of(1L, product(1, false, false, StockRow.REJECTED)), inactiveMerchant,
                OptionalLong.of(MERCHANT), 1)).containsExactly(new RefusedLine(1, RefusalReason.OWN_PRODUCT));
        assertThat(refused(Map.of(1L, product(1, false, false, StockRow.REJECTED)), inactiveMerchant,
                OptionalLong.empty(), 1)).containsExactly(new RefusedLine(1, RefusalReason.INACTIVE));
        assertThat(refused(Map.of(1L, product(1, true, false, StockRow.REJECTED)), inactiveMerchant,
                OptionalLong.empty(), 1)).containsExactly(new RefusedLine(1, RefusalReason.MERCHANT_INACTIVE));
    }

    /** Every refused line, in cart order, the good ones left out. Mutation: stop at the first refused line. */
    @Test
    void everyRefusedLineIsListedInCartOrder() {
        Map<Long, CatalogProduct> products = Map.of(
                5L, product(5, false, true, StockRow.CREATED),
                2L, product(2, true, true, StockRow.CREATED),
                7L, product(7, true, true, StockRow.CONFLICT));

        assertThat(refused(products, MERCHANT_ACTIVE, OptionalLong.empty(), 5, 2, 9, 7)).containsExactly(
                new RefusedLine(5, RefusalReason.INACTIVE),
                new RefusedLine(9, RefusalReason.NOT_FOUND),
                new RefusedLine(7, RefusalReason.STOCK_ROW_UNAVAILABLE));
    }
}
