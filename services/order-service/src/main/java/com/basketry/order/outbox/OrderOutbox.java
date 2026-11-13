package com.basketry.order.outbox;

import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderPlacement.Placed;
import com.basketry.order.outbox.OrderRequests.MerchantChargeRequested;
import com.basketry.order.outbox.OrderRequests.MerchantRefundRequested;
import com.basketry.order.outbox.OrderRequests.StockReleaseRequested;
import com.basketry.order.outbox.OrderRequests.StockReserveRequested;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes order-service's outbox rows (DESIGN.md Section 5, order-service
 * requests). Every method runs in the caller's transaction, the one that wrote
 * or changed the order, so the change and its rows commit together or not at
 * all. Every row is keyed by orderId and gets a new eventId.
 */
@Service
public class OrderOutbox {

    private final OutboxRepository outbox;
    private final CardNumberScrub scrub;
    private final JsonMapper json;
    private final Clock clock;

    public OrderOutbox(OutboxRepository outbox, CardNumberScrub scrub, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.scrub = scrub;
        this.json = json;
        this.clock = clock;
    }

    /** Checkout's requests: a charge per charge leg, then a reserve per line. Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCheckoutRequests(Placed placed, String cardNumber) {
        long orderId = placed.order().getId();
        for (MerchantCharge charge : placed.charges()) {
            UUID eventId = UUID.randomUUID();
            Instant now = clock.instant();
            write(OrderRequests.CHARGE_TOPIC, orderId, eventId, now, new MerchantChargeRequested(
                    OrderRequests.SCHEMA_VERSION, eventId, orderId, charge.getMerchantId(), charge.getAmount(),
                    cardNumber, now));
        }
        for (OrderLine line : placed.lines()) {
            UUID eventId = UUID.randomUUID();
            Instant now = clock.instant();
            write(OrderRequests.RESERVE_TOPIC, orderId, eventId, now, new StockReserveRequested(
                    OrderRequests.SCHEMA_VERSION, eventId, orderId, line.getProductId(), line.getQuantity(), now));
        }
    }

    /** A refund of a charged leg, by its transactionRef. Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRefund(long orderId, long merchantId, String transactionRef) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        write(OrderRequests.REFUND_TOPIC, orderId, eventId, now, new MerchantRefundRequested(
                OrderRequests.SCHEMA_VERSION, eventId, orderId, merchantId, transactionRef, now));
    }

    /** A release of a line, with the line's quantity. Fails if no transaction is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRelease(long orderId, long productId, long quantity) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        write(OrderRequests.RELEASE_TOPIC, orderId, eventId, now, new StockReleaseRequested(
                OrderRequests.SCHEMA_VERSION, eventId, orderId, productId, quantity, now));
    }

    /**
     * The timer's cancel (DESIGN.md Section 2, order-service): every row of the
     * order still PENDING on a charge or reserve topic becomes CANCELLED, with
     * its card number removed, in the caller's transaction. A row the relay is
     * sending at that moment can still reach the broker; whichever of the two
     * marks comes first wins, and each removes the card number.
     *
     * @return how many rows this call cancelled
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelUnsentRequests(long orderId) {
        List<OutboxEvent> unsent = outbox.findByMessageKeyAndStatusAndTopicInOrderByIdAsc(
                Long.toString(orderId), OutboxStatus.PENDING, OrderRequests.CANCELLABLE_TOPICS);
        int cancelled = 0;
        for (OutboxEvent row : unsent) {
            cancelled += outbox.markCancelled(row.getId(), scrub.scrubbed(row.getPayload()));
        }
        return cancelled;
    }

    private void write(String topic, long orderId, UUID eventId, Instant now, Object request) {
        outbox.save(new OutboxEvent(eventId, topic, Long.toString(orderId), json.writeValueAsString(request), now));
    }
}
