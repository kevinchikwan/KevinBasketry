package com.basketry.order.checkout;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.CheckoutRefusedException.RefusedLine;
import com.basketry.order.downstream.CatalogClient.CatalogProduct;
import com.basketry.order.downstream.CatalogClient.StockRow;
import com.basketry.order.downstream.MerchantClient.MerchantStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Which cart lines cannot be bought (DESIGN.md Section 2, order-service,
 * checkout step 5): every one of them, in cart order, each with the first
 * reason that applies. Pure: the answers of catalog-service, merchant-service
 * and identity-service go in. Stock itself is not checked here; the
 * reservation decides it, and a PENDING stock row fails there with
 * UNKNOWN_PRODUCT.
 */
final class CartRules {

    private CartRules() {
    }

    /**
     * @param buyerMerchantId the merchant linked to the buyer's account, live from identity-service
     */
    static List<RefusedLine> refused(List<CheckoutLine> lines, Map<Long, CatalogProduct> products,
                                     Map<Long, MerchantStatus> merchants, OptionalLong buyerMerchantId) {
        List<RefusedLine> refused = new ArrayList<>();
        for (CheckoutLine line : lines) {
            RefusalReason reason = reason(products.get(line.productId()), merchants, buyerMerchantId);
            if (reason != null) {
                refused.add(new RefusedLine(line.productId(), reason));
            }
        }
        return refused;
    }

    private static RefusalReason reason(CatalogProduct product, Map<Long, MerchantStatus> merchants,
                                        OptionalLong buyerMerchantId) {
        if (product == null) {
            return RefusalReason.NOT_FOUND;
        }
        if (buyerMerchantId.isPresent() && buyerMerchantId.getAsLong() == product.merchantId()) {
            return RefusalReason.OWN_PRODUCT;
        }
        if (!product.active()) {
            return RefusalReason.INACTIVE;
        }
        MerchantStatus merchant = merchants.get(product.merchantId());
        if (!product.merchantActive() || merchant == null || !merchant.active()) {
            return RefusalReason.MERCHANT_INACTIVE;
        }
        if (product.stockRow() == StockRow.CONFLICT || product.stockRow() == StockRow.REJECTED) {
            return RefusalReason.STOCK_ROW_UNAVAILABLE;
        }
        return null;
    }
}
