package com.basketry.order.checkout;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.CheckoutRefusedException.BuyerRefused;
import com.basketry.order.checkout.CheckoutRefusedException.CartNotPurchasable;
import com.basketry.order.checkout.CheckoutRefusedException.CurrentPrice;
import com.basketry.order.checkout.CheckoutRefusedException.IdempotencyKeyReused;
import com.basketry.order.checkout.CheckoutRefusedException.PriceChanged;
import com.basketry.order.checkout.CheckoutRefusedException.RefusedLine;
import com.basketry.order.checkout.CheckoutRefusedException.Unavailable;
import com.basketry.order.config.FingerprintKey;
import com.basketry.order.config.InternalApiKey;
import com.basketry.order.downstream.CatalogClient;
import com.basketry.order.downstream.CatalogClient.CatalogProduct;
import com.basketry.order.downstream.DownstreamUnavailableException;
import com.basketry.order.downstream.IdentityClient;
import com.basketry.order.downstream.IdentityClient.AccountNotFoundException;
import com.basketry.order.downstream.IdentityClient.BuyerAccount;
import com.basketry.order.downstream.MerchantClient;
import com.basketry.order.downstream.MerchantClient.MerchantStatus;
import com.basketry.order.order.Amounts;
import com.basketry.order.order.Amounts.PricedLine;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.CustomerOrderRepository;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.MerchantChargeRepository;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderLineRepository;
import com.basketry.order.order.OrderPlacement;
import com.basketry.order.order.OrderPlacement.Placed;
import com.basketry.order.order.OrderReferences;
import com.basketry.order.outbox.OrderOutbox;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout (DESIGN.md Section 2, order-service, checkout in order). The body
 * rules (step 1) are {@link CheckoutCommand}'s and the capability (step 2) the
 * web layer's; this class runs the rest:
 *
 * <ol start="3">
 *   <li>the fingerprint key must be configured;</li>
 *   <li>the Idempotency-Key: the same key and body answers the order as it
 *       stands, another body is IDEMPOTENCY_KEY_REUSED, and nothing below runs
 *       again;</li>
 *   <li>the internal key, then identity-service, catalog-service and
 *       merchant-service, the cart rules and the price;</li>
 *   <li>one transaction: the order, its legs and its requests in the outbox.</li>
 * </ol>
 *
 * <p>Not {@code @Transactional}: no transaction is open during a call to
 * another service. A refusal stores nothing, so a retry with the same key runs
 * again from the start.
 */
@Service
public class CheckoutService {

    static final String UNIQUE_BUYER_KEY = "uq_customer_order_buyer_key";

    private final FingerprintKey fingerprintKey;
    private final InternalApiKey internalApiKey;
    private final IdentityClient identity;
    private final CatalogClient catalog;
    private final MerchantClient merchantService;
    private final CouponValidator coupons;
    private final CustomerOrderRepository orders;
    private final MerchantChargeRepository charges;
    private final OrderLineRepository lines;
    private final OrderPlacement placement;
    private final OrderOutbox outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration sagaDeadline;

    public CheckoutService(FingerprintKey fingerprintKey, InternalApiKey internalApiKey, IdentityClient identity,
                           CatalogClient catalog, MerchantClient merchantService, CouponValidator coupons,
                           CustomerOrderRepository orders, MerchantChargeRepository charges,
                           OrderLineRepository lines, OrderPlacement placement, OrderOutbox outbox,
                           PlatformTransactionManager transactionManager, Clock clock,
                           @Value("${basketry.order.saga-deadline}") Duration sagaDeadline) {
        this.fingerprintKey = fingerprintKey;
        this.internalApiKey = internalApiKey;
        this.identity = identity;
        this.catalog = catalog;
        this.merchantService = merchantService;
        this.coupons = coupons;
        this.orders = orders;
        this.charges = charges;
        this.lines = lines;
        this.placement = placement;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.sagaDeadline = sagaDeadline;
    }

    /**
     * @param buyer the caller: the token's account and the token itself, forwarded to identity-service
     * @return the new order ({@code created}), or the one an earlier request with this key created
     * @throws CheckoutRefusedException for every refusal after the body; nothing is stored
     */
    public CheckoutResult checkout(CheckoutCommand command, Buyer buyer) {
        String fingerprint = fingerprintKey.hmac(RequestFingerprint.canonical(command))
                .orElseThrow(() -> new Unavailable(Unavailable.FINGERPRINT_KEY_NOT_CONFIGURED,
                        "BASKETRY_ORDER_FINGERPRINT_KEY is not set."));
        Optional<CheckoutResult> replay = replay(buyer, command, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (internalApiKey.value().isEmpty()) {
            throw new Unavailable(Unavailable.INTERNAL_API_KEY_NOT_CONFIGURED, "BASKETRY_INTERNAL_API_KEY is not set.");
        }

        BuyerAccount account = buyerAccount(buyer);
        Map<Long, CatalogProduct> products = downstream(() -> catalog.products(productIds(command)));
        Map<Long, MerchantStatus> merchants = products.isEmpty() ? Map.of()
                : downstream(() -> merchantService.merchants(products.values().stream()
                        .map(CatalogProduct::merchantId).collect(Collectors.toCollection(TreeSet::new))));
        List<RefusedLine> refusedLines = CartRules.refused(command.lines(), products, merchants, account.merchantId());
        if (!refusedLines.isEmpty()) {
            throw new CartNotPurchasable(refusedLines);
        }

        List<PricedLine> priced = command.lines().stream()
                .map(line -> priced(line, products.get(line.productId())))
                .toList();
        Map<Long, BigDecimal> rates = merchants.values().stream()
                .collect(Collectors.toMap(MerchantStatus::id, MerchantStatus::commissionRate));
        Amounts amounts = Amounts.of(priced, rates, coupons.discounts(command.couponCode(), priced));
        if (amounts.total().compareTo(command.expectedTotal()) != 0) {
            throw new PriceChanged(priced.stream().map(line -> new CurrentPrice(line.productId(), line.unitPrice()))
                    .toList(), amounts.total());
        }
        return place(command, buyer, fingerprint, amounts);
    }

    private Optional<CheckoutResult> replay(Buyer buyer, CheckoutCommand command, String fingerprint) {
        return orders.findByBuyerAccountIdAndIdempotencyKey(buyer.accountId(), command.idempotencyKey())
                .map(existing -> {
                    if (!existing.getRequestFingerprint().equals(fingerprint)) {
                        throw new IdempotencyKeyReused();
                    }
                    return asStored(existing);
                });
    }

    private BuyerAccount buyerAccount(Buyer buyer) {
        BuyerAccount account;
        try {
            account = identity.account(buyer.accountId(), buyer.bearerToken());
        } catch (AccountNotFoundException missing) {
            throw new BuyerRefused(BuyerRefused.BUYER_NOT_FOUND, "identity-service does not know this account.");
        } catch (DownstreamUnavailableException unavailable) {
            throw new Unavailable(unavailable.code(), unavailable.getMessage());
        }
        if (!account.buyerActive()) {
            throw new BuyerRefused(BuyerRefused.BUYER_DEACTIVATED, "This buyer account is deactivated.");
        }
        return account;
    }

    private static <T> T downstream(Supplier<T> call) {
        try {
            return call.get();
        } catch (DownstreamUnavailableException unavailable) {
            throw new Unavailable(unavailable.code(), unavailable.getMessage());
        }
    }

    private static TreeSet<Long> productIds(CheckoutCommand command) {
        return command.lines().stream().map(CheckoutLine::productId).collect(Collectors.toCollection(TreeSet::new));
    }

    private static PricedLine priced(CheckoutLine line, CatalogProduct product) {
        return new PricedLine(product.id(), product.merchantId(), product.name(), product.price(),
                Math.toIntExact(line.quantity()));
    }

    /**
     * Step 6. Two concurrent first requests with one key both get here; the
     * second insert breaks uq_customer_order_buyer_key, and a new transaction
     * answers from the winner's order: on PostgreSQL the failed one can run
     * nothing more.
     */
    private CheckoutResult place(CheckoutCommand command, Buyer buyer, String fingerprint, Amounts amounts) {
        Instant now = clock.instant();
        CustomerOrder order = new CustomerOrder(OrderReferences.next(), buyer.accountId(), command.idempotencyKey(),
                fingerprint, command.couponCode(), amounts.total(), now, now.plus(sagaDeadline));
        try {
            Placed placed = transactions.execute(status -> {
                Placed written = placement.place(order, amounts);
                outbox.recordCheckoutRequests(written, command.cardNumber());
                return written;
            });
            return new CheckoutResult(placed.order(), placed.charges(), placed.lines(), true);
        } catch (DataIntegrityViolationException lostRace) {
            if (!String.valueOf(lostRace.getMessage()).toLowerCase(Locale.ROOT).contains(UNIQUE_BUYER_KEY)) {
                throw lostRace;
            }
            return transactions.execute(status -> replay(buyer, command, fingerprint)).orElseThrow(() -> lostRace);
        }
    }

    private CheckoutResult asStored(CustomerOrder order) {
        List<MerchantCharge> storedCharges = charges.findByOrderIdOrderByMerchantIdAsc(order.getId());
        List<OrderLine> storedLines = lines.findByOrderIdOrderByProductIdAsc(order.getId());
        return new CheckoutResult(order, storedCharges, storedLines, false);
    }

    /**
     * @param created true for a new order (202), false for a replay of an earlier request (200)
     */
    public record CheckoutResult(CustomerOrder order, List<MerchantCharge> charges, List<OrderLine> lines,
                                 boolean created) {
    }

    /** The caller. {@link #toString()} never shows the token. */
    public record Buyer(long accountId, String bearerToken) {

        @Override
        public String toString() {
            return "Buyer[accountId=" + accountId + ", bearerToken=***]";
        }
    }
}
