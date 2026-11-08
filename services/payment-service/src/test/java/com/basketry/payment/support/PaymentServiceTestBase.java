package com.basketry.payment.support;

import com.basketry.payment.outbox.OutboxEvent;
import com.basketry.payment.outbox.OutboxRepository;
import com.basketry.payment.payment.ChargeCommand;
import com.basketry.payment.payment.PaymentService;
import com.basketry.payment.payment.PaymentTransaction;
import com.basketry.payment.payment.PaymentTransactionRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every Spring test of payment-service: the real application on the test
 * database (H2, or PostgreSQL in postgresTest), emptied before each test.
 */
@SpringBootTest
public abstract class PaymentServiceTestBase {

    /** Approved by the mock (DESIGN.md Section 2, payment-service). */
    protected static final String APPROVED_CARD = "4000000000000002";
    protected static final String INSUFFICIENT_FUNDS_CARD = "4000000000000010";
    protected static final String DO_NOT_HONOR_CARD = "4000000000000028";
    protected static final String EXPIRED_CARD = "4000000000000036";

    @Autowired
    protected PaymentService payments;

    @Autowired
    protected PaymentTransactionRepository transactions;

    @Autowired
    protected OutboxRepository outbox;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void emptyTheDatabase() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM payment_transaction");
    }

    protected static ChargeCommand charge(long orderId, long merchantId, String amount, String cardNumber) {
        return new ChargeCommand(orderId, merchantId, new BigDecimal(amount), cardNumber);
    }

    protected PaymentTransaction onlyTransaction() {
        List<PaymentTransaction> all = transactions.findAll();
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one transaction row, found " + all.size());
        }
        return all.getFirst();
    }

    protected List<OutboxEvent> outboxRows() {
        return outbox.findAllByOrderByIdAsc();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> payloadOf(OutboxEvent event) {
        return json.readValue(event.getPayload(), Map.class);
    }

    /** Every value of every row of both tables, as text: what a database dump would show. */
    protected List<String> everyStoredValue() {
        return java.util.stream.Stream.of("payment_transaction", "outbox_event")
                .flatMap(table -> jdbc.queryForList("SELECT * FROM " + table).stream())
                .flatMap(row -> row.values().stream())
                .map(String::valueOf)
                .toList();
    }

    /**
     * The real repository, except that its first idempotency lookup finds
     * nothing. A service built on it believes a request is new when its row
     * already exists, which is exactly the losing side of a race: its insert
     * must hit the unique constraint.
     */
    protected PaymentTransactionRepository firstLookupMisses() {
        AtomicBoolean missed = new AtomicBoolean();
        return (PaymentTransactionRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PaymentTransactionRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByOrderIdAndMerchantId") && missed.compareAndSet(false, true)) {
                        return Optional.empty();
                    }
                    try {
                        return method.invoke(transactions, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    protected static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
