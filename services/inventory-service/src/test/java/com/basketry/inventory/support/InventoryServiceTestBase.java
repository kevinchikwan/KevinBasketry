package com.basketry.inventory.support;

import com.basketry.inventory.counter.StockCounter;
import com.basketry.inventory.counter.StockCounterGuard;
import com.basketry.inventory.counter.StockCounterProperties;
import com.basketry.inventory.outbox.OutboxEvent;
import com.basketry.inventory.outbox.OutboxRepository;
import com.basketry.inventory.outbox.StockResultOutbox;
import com.basketry.inventory.stock.ReleaseCommand;
import com.basketry.inventory.stock.ReserveCommand;
import com.basketry.inventory.stock.StockLevelRepository;
import com.basketry.inventory.stock.StockReservation;
import com.basketry.inventory.stock.StockReservationRepository;
import com.basketry.inventory.stock.StockService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every Spring test of inventory-service: the real application on the test
 * database (H2, or PostgreSQL in postgresTest) and the test counter (the
 * in-memory fake, or a real Redis in postgresTest), all emptied before each
 * test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestStockCounterConfig.class)
public abstract class InventoryServiceTestBase {

    protected static final long MERCHANT = 3;

    /** The value of basketry.internal.api-key in the test application.properties. */
    public static final String API_KEY = "test-only-internal-key-not-a-secret-000";

    /** Tokens are verified against a fake identity-service publishing TestTokens.KEY. */
    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeIdentityServer.shared().baseUrl());
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected StockService stock;

    /** The fake in the default run, Redis in postgresTest. */
    @Autowired
    protected StockCounter counter;

    @Autowired
    protected StockLevelRepository stockLevels;

    @Autowired
    protected StockReservationRepository reservations;

    @Autowired
    protected OutboxRepository outbox;

    @Autowired
    protected StockResultOutbox resultOutbox;

    @Autowired
    protected StockCounterProperties counterProperties;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void emptyTheDatabaseAndTheCounters() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM stock_reservation");
        jdbc.update("DELETE FROM stock_level");
        if (counter instanceof InMemoryStockCounter fake) {
            fake.clear();
        } else {
            redis.execute((RedisCallback<Object>) connection -> {
                connection.serverCommands().flushDb();
                return null;
            });
        }
    }

    /** A stock row with any quantity, even above what an adjustment allows. The counter is not loaded. */
    protected void givenStock(long productId, long quantity) {
        jdbc.update("INSERT INTO stock_level (product_id, merchant_id, quantity, created_at, updated_at)"
                + " VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", productId, MERCHANT, quantity);
    }

    /** A stock row and its counter, both at {@code quantity}. */
    protected void givenStockAndCounter(long productId, long quantity) {
        givenStock(productId, quantity);
        counter.overwrite(productId, quantity);
    }

    protected long quantityOf(long productId) {
        return stockLevels.findQuantity(productId).orElseThrow();
    }

    protected OptionalLong counterOf(long productId) {
        return counter.current(productId);
    }

    protected StockReservation reservation(long orderId, long productId) {
        return reservations.findByOrderIdAndProductId(orderId, productId).orElseThrow();
    }

    protected List<OutboxEvent> outboxRows() {
        return outbox.findAllByOrderByIdAsc();
    }

    protected List<String> topics() {
        return outboxRows().stream().map(OutboxEvent::getTopic).toList();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> payloadOf(OutboxEvent event) {
        return json.readValue(event.getPayload(), Map.class);
    }

    /** A request with a bearer token. */
    protected static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** A request with the internal API key. */
    protected static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header("X-Internal-Api-Key", API_KEY);
    }

    /** A JSON request body. */
    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    /** Exactly one log line contains {@code text}, and it is a WARN. */
    protected static void assertWarnedOnce(org.springframework.boot.test.system.CapturedOutput output, String text) {
        org.assertj.core.api.Assertions.assertThat(output.getAll().lines().filter(line -> line.contains(text)))
                .singleElement().asString().contains(" WARN ");
    }

    protected static ReserveCommand reserve(long orderId, long productId, int quantity) {
        return new ReserveCommand(orderId, productId, quantity);
    }

    protected static ReleaseCommand release(long orderId, long productId, int quantity) {
        return new ReleaseCommand(orderId, productId, quantity);
    }

    /** A service on the given counter (a fake with faults), with everything else real. */
    protected StockService serviceOn(StockCounter counter, Clock clock) {
        return new StockService(stockLevels, reservations, resultOutbox,
                new StockCounterGuard(counter, counterProperties, clock), transactionManager);
    }

    /** A service whose reservation repository is replaced, with the real counter guard. */
    protected StockService serviceWith(StockReservationRepository reservationRepository) {
        return new StockService(stockLevels, reservationRepository, resultOutbox,
                new StockCounterGuard(counter, counterProperties), transactionManager);
    }

    /**
     * The real repository, except that its first idempotency lookup answers
     * {@code firstAnswer}. With an empty answer, a service built on it believes
     * a request is new when its row already exists: the losing side of a race.
     * With a stale copy of a row, it believes the row is still in that state.
     */
    protected StockReservationRepository firstLookupAnswers(Optional<StockReservation> firstAnswer) {
        AtomicBoolean answered = new AtomicBoolean();
        return (StockReservationRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {StockReservationRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByOrderIdAndProductId") && answered.compareAndSet(false, true)) {
                        return firstAnswer;
                    }
                    try {
                        return method.invoke(reservations, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
