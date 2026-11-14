package com.basketry.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.order.CustomerOrder;
import com.basketry.order.support.SagaTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The golden files of order.succeeded and order.failed
 * (src/test/resources/contracts, DESIGN.md Section 5, with the values of its
 * examples): each event order-service writes must be exactly one of them, byte
 * for byte, once the values that change per order (eventId, orderId,
 * orderReference) are put back. The events are made by the real saga at T0.
 */
class OrderEventFixtureTests extends SagaTestBase {

    private static String fixture(String name) {
        try (InputStream in = OrderEventFixtureTests.class.getResourceAsStream("/contracts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private String normalised(OutboxEvent row, CustomerOrder order, String fixture) {
        Map<String, Object> golden = json.readValue(fixture, Map.class);
        return row.getPayload()
                .replace("\"eventId\":\"" + row.getEventId() + "\"", "\"eventId\":\"" + golden.get("eventId") + "\"")
                .replace("\"orderId\":" + order.getId() + ",", "\"orderId\":" + golden.get("orderId") + ",")
                .replace(order.getOrderReference(), (String) golden.get("orderReference"));
    }

    /** One line of product 42 at 8.50, 3 units, merchant 3 at 5.00%. */
    private long order42() {
        givenBuyer();
        services.givenProduct(42, MERCHANT, "Tea", "8.50", true, true, "CREATED");
        givenMerchant(MERCHANT, "5.00");
        return checkout(command("key-fixture-" + System.nanoTime(), "25.50", 42, 3)).order().getId();
    }

    /**
     * Mutations: the commission left out of a merchant; the item's lineAmount
     * renamed (amount); the merchants' transactionRef left out; couponCode
     * left out when null (NON_NULL on the event).
     */
    @Test
    void orderSucceededIsTheGoldenFile() {
        long id = order42();
        charged(id, MERCHANT);
        reserved(id, 42);

        OutboxEvent event = rows(id, OrderRequests.SUCCEEDED_TOPIC).getFirst();
        assertThat(normalised(event, orderOf(id), fixture("order-succeeded.json")))
                .isEqualTo(fixture("order-succeeded.json"));
    }

    /**
     * Charges first, then lines; merchantId only for a CHARGE, productId only
     * for a RESERVE. Mutations: NON_NULL removed from Reason ("productId":null
     * written); the lines' reasons first.
     */
    @Test
    void orderFailedIsTheGoldenFile() {
        long id = order42();
        reservationFailed(id, 42, "INSUFFICIENT_STOCK");
        declined(id, MERCHANT, "51");

        OutboxEvent event = rows(id, OrderRequests.FAILED_TOPIC).getFirst();
        assertThat(normalised(event, orderOf(id), fixture("order-failed.json"))).isEqualTo(fixture("order-failed.json"));
    }
}
