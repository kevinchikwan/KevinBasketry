package com.basketry.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.support.OrderServiceTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The golden request files (src/test/resources/contracts, DESIGN.md Section 5,
 * order-service requests): every request order-service writes must be exactly
 * one of them, byte for byte, once the two values that change per row (the
 * eventId and the orderId) are put back. The files are the contract with
 * payment-service's and inventory-service's parsers; the end-to-end check
 * proves the real parsers accept them. Each request is made by the real code,
 * at a fixed time.
 */
@Import(ContractFixtureTests.FixedClock.class)
class ContractFixtureTests extends OrderServiceTestBase {

    private static final Instant AT = Instant.parse("2026-11-08T10:00:00Z");

    @TestConfiguration
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    private OrderOutbox orderOutbox;

    private static String fixture(String name) {
        try (InputStream in = ContractFixtureTests.class.getResourceAsStream("/contracts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The payload with this row's eventId and orderId replaced by the fixture's. */
    @SuppressWarnings("unchecked")
    private String normalised(OutboxEvent row, String fixture) {
        Map<String, Object> golden = json.readValue(fixture, Map.class);
        return row.getPayload()
                .replace("\"eventId\":\"" + row.getEventId() + "\"", "\"eventId\":\"" + golden.get("eventId") + "\"")
                .replace("\"orderId\":" + row.getMessageKey() + ",", "\"orderId\":" + golden.get("orderId") + ",");
    }

    /**
     * Mutations: a field renamed (orderId as order_id); the amount written as a
     * double (25.5); the quantity written as a string; a field added (currency);
     * occurredAt as epoch numbers.
     */
    @Test
    void checkoutsChargeAndReserveAreTheGoldenFiles() {
        givenBuyer();
        services.givenProduct(42, MERCHANT, "Tea", "8.50", true, true, "CREATED");
        givenMerchant(MERCHANT, "5.00");

        checkout(command("key-0001", "25.50", 42, 3));

        List<OutboxEvent> rows = outboxRows();
        assertThat(normalised(rows.get(0), fixture("merchant-charge-requested.json")))
                .isEqualTo(fixture("merchant-charge-requested.json"));
        assertThat(normalised(rows.get(1), fixture("stock-reserve-requested.json")))
                .isEqualTo(fixture("stock-reserve-requested.json"));
    }

    /** Mutations: the transactionRef field renamed (ref); the release's quantity left out. */
    @Test
    void theRefundAndTheReleaseAreTheGoldenFiles() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderOutbox.recordRefund(7, MERCHANT, "TXN-0123456789abcdef0123456789abcdef");
            orderOutbox.recordRelease(7, 42, 3);
        });

        List<OutboxEvent> rows = outboxRows();
        assertThat(normalised(rows.get(0), fixture("merchant-refund-requested.json")))
                .isEqualTo(fixture("merchant-refund-requested.json"));
        assertThat(normalised(rows.get(1), fixture("stock-release-requested.json")))
                .isEqualTo(fixture("stock-release-requested.json"));
    }
}
