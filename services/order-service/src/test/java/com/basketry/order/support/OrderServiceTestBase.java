package com.basketry.order.support;

import com.basketry.order.checkout.CheckoutCommand;
import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.CheckoutService;
import com.basketry.order.checkout.CheckoutService.Buyer;
import com.basketry.order.checkout.CheckoutService.CheckoutResult;
import com.basketry.order.order.CustomerOrderRepository;
import com.basketry.order.order.MerchantChargeRepository;
import com.basketry.order.order.OrderLineRepository;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every Spring test of order-service: the real application on the test
 * database (H2, or PostgreSQL in postgresTest), emptied before each test, with
 * identity-service, catalog-service and merchant-service played by
 * {@link FakeServices} over real HTTP.
 */
@SpringBootTest
public abstract class OrderServiceTestBase {

    /** The value of basketry.internal.api-key in the test application.properties. */
    public static final String API_KEY = "test-only-internal-key-not-a-secret-000";

    /** Approved by payment-service's mock. */
    protected static final String CARD = "4000000000000002";

    protected static final long BUYER = 12;
    protected static final String BUYER_TOKEN = "buyer-12-token";
    protected static final long MERCHANT = 3;
    protected static final long OTHER_MERCHANT = 4;

    @DynamicPropertySource
    static void downstreamServices(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeServices.shared().baseUrl());
        registry.add("basketry.catalog.base-url", () -> FakeServices.shared().baseUrl());
        registry.add("basketry.merchant.base-url", () -> FakeServices.shared().baseUrl());
    }

    protected final FakeServices services = FakeServices.shared();

    @Autowired
    protected CheckoutService checkoutService;

    @Autowired
    protected CustomerOrderRepository orders;

    @Autowired
    protected MerchantChargeRepository charges;

    @Autowired
    protected OrderLineRepository lines;

    @Autowired
    protected OutboxRepository outbox;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyTheDatabaseAndTheServices() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM order_line");
        jdbc.update("DELETE FROM merchant_charge");
        jdbc.update("DELETE FROM customer_order");
        services.reset();
    }

    /** An active buyer with no merchant link, whose token identity-service accepts. */
    protected void givenBuyer() {
        services.givenAccount(BUYER, BUYER_TOKEN, true, null);
    }

    /** An active product of an active merchant with a created stock row. */
    protected void givenProduct(long id, long merchantId, String price) {
        services.givenProduct(id, merchantId, "Product " + id, price, true, true, "CREATED");
    }

    protected void givenMerchant(long id, String commissionRate) {
        services.givenMerchant(id, true, commissionRate);
    }

    protected static Buyer buyer() {
        return new Buyer(BUYER, BUYER_TOKEN);
    }

    /** {@code lines} as productId, quantity, productId, quantity ... */
    protected static CheckoutCommand command(String key, String expectedTotal, long... lines) {
        return command(key, CARD, expectedTotal, lines);
    }

    protected static CheckoutCommand command(String key, String card, String expectedTotal, long... lines) {
        List<CheckoutLine> cart = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            cart.add(new CheckoutLine(lines[i], lines[i + 1]));
        }
        return new CheckoutCommand(key, cart, null, card, new BigDecimal(expectedTotal));
    }

    protected CheckoutResult checkout(CheckoutCommand command) {
        return checkoutService.checkout(command, buyer());
    }

    protected List<OutboxEvent> outboxRows() {
        return outbox.findAllByOrderByIdAsc();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> payloadOf(OutboxEvent event) {
        return json.readValue(event.getPayload(), Map.class);
    }

    /** How many rows each of the order tables holds. */
    protected List<Integer> rowCounts() {
        return List.of("customer_order", "merchant_charge", "order_line", "outbox_event").stream()
                .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                .toList();
    }

    /** Every value of every row of every table, as text: what a database dump would show. */
    protected List<String> everyStoredValue() {
        return List.of("customer_order", "merchant_charge", "order_line", "outbox_event").stream()
                .flatMap(table -> jdbc.queryForList("SELECT * FROM " + table).stream())
                .flatMap(row -> row.values().stream())
                .map(String::valueOf)
                .toList();
    }
}
