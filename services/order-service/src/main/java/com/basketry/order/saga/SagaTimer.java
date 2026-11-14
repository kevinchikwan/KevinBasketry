package com.basketry.order.saga;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.CustomerOrderRepository;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.MerchantChargeRepository;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderLineRepository;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The timer (DESIGN.md Section 2, order-service, the timer). Each pass takes up
 * to a batch of orders with something due, and handles each in its own
 * transaction under the order's row lock:
 *
 * <ul>
 *   <li><b>The deadline.</b> Past it, every leg still REQUESTED is TIMED_OUT
 *       (NO_RESULT). A timed-out line gets its release at once, whether its
 *       reserve was sent or not; a timed-out charge has nothing to refund. The
 *       order's unsent charges and reserves are CANCELLED, their card numbers
 *       removed. Then the decision: a PENDING order becomes FAILING.</li>
 *   <li><b>Compensations.</b> A refund or release not confirmed by its due
 *       time (TIMED_OUT lines included) is sent again, at most maxResends
 *       times, resendInterval apart; once the last is due unconfirmed, the leg
 *       is STUCK and the order needs attention. A STUCK leg is not resent.</li>
 *   <li><b>Startup grace.</b> For startupGrace after the service starts, a
 *       pass does nothing, so results still queued in Kafka are read
 *       first.</li>
 * </ul>
 *
 * <p>Two instances are safe: the order's row lock decides who acts, and every
 * change depends on the leg's state.
 */
@Service
public class SagaTimer {

    private static final Logger log = LoggerFactory.getLogger(SagaTimer.class);

    private final CustomerOrderRepository orders;
    private final MerchantChargeRepository charges;
    private final OrderLineRepository lines;
    private final OrderOutbox outbox;
    private final SagaDecision decision;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SagaProperties properties;
    private final Instant startedAt;

    public SagaTimer(CustomerOrderRepository orders, MerchantChargeRepository charges, OrderLineRepository lines,
                     OrderOutbox outbox, SagaDecision decision, PlatformTransactionManager transactionManager,
                     Clock clock, SagaProperties properties) {
        this.orders = orders;
        this.charges = charges;
        this.lines = lines;
        this.outbox = outbox;
        this.decision = decision;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
        this.startedAt = clock.instant();
    }

    /** One pass. @return how many orders it handled; 0 during the startup grace. */
    public int pass() {
        Instant now = clock.instant();
        if (now.isBefore(startedAt.plus(properties.startupGrace()))) {
            return 0;
        }
        int batch = properties.timer().batchSize();
        TreeSet<Long> due = new TreeSet<>();
        due.addAll(orders.findIdsPastDeadline(now, Limit.of(batch)));
        due.addAll(orders.findIdsWithRefundDue(now, Limit.of(batch)));
        due.addAll(orders.findIdsWithReleaseDue(now, Limit.of(batch)));
        int handled = 0;
        for (Long orderId : due) {
            if (handled == batch) {
                break;
            }
            try {
                transactions.executeWithoutResult(status -> step(orderId, now));
            } catch (RuntimeException failed) {
                // For example the row lock timing out: the next pass tries again.
                log.warn("The timer could not handle order {}: {}", orderId, failed.toString());
            }
            handled++;
        }
        return handled;
    }

    private void step(long orderId, Instant now) {
        Optional<CustomerOrder> locked = orders.findByIdForUpdate(orderId);
        if (locked.isEmpty()) {
            return;
        }
        CustomerOrder order = locked.get();
        List<MerchantCharge> orderCharges = charges.findByOrderIdOrderByMerchantIdAsc(orderId);
        List<OrderLine> orderLines = lines.findByOrderIdOrderByProductIdAsc(orderId);
        Instant nextDue = now.plus(properties.compensation().resendInterval());
        if ((order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.FAILING)
                && !now.isBefore(order.getDeadlineAt())) {
            timeOut(order, orderCharges, orderLines, nextDue);
        }
        for (MerchantCharge charge : orderCharges) {
            if (charge.getStatus() == ChargeStatus.REFUND_REQUESTED && isDue(charge.getCompensationDueAt(), now)) {
                if (charge.getResends() < properties.compensation().maxResends()) {
                    charge.refundResent(nextDue);
                    outbox.recordRefund(orderId, charge.getMerchantId(), charge.getTransactionRef());
                } else {
                    charge.stuck();
                    log.error("The refund of order {}, merchant {} is STUCK: never confirmed", orderId,
                            charge.getMerchantId());
                }
            }
        }
        for (OrderLine line : orderLines) {
            if ((line.getStatus() == LineStatus.RELEASE_REQUESTED || line.getStatus() == LineStatus.TIMED_OUT)
                    && isDue(line.getCompensationDueAt(), now)) {
                if (line.getResends() < properties.compensation().maxResends()) {
                    line.releaseResent(nextDue);
                    outbox.recordRelease(orderId, line.getProductId(), line.getQuantity());
                } else {
                    line.stuck();
                    log.error("The release of order {}, product {} is STUCK: never confirmed", orderId,
                            line.getProductId());
                }
            }
        }
        decision.decide(order, orderCharges, orderLines);
    }

    private void timeOut(CustomerOrder order, List<MerchantCharge> orderCharges, List<OrderLine> orderLines,
                         Instant releaseDue) {
        boolean timedOut = false;
        for (MerchantCharge charge : orderCharges) {
            if (charge.getStatus() == ChargeStatus.REQUESTED) {
                charge.timedOut();
                timedOut = true;
            }
        }
        for (OrderLine line : orderLines) {
            if (line.getStatus() == LineStatus.REQUESTED) {
                line.timedOut(releaseDue);
                outbox.recordRelease(order.getId(), line.getProductId(), line.getQuantity());
                timedOut = true;
            }
        }
        if (timedOut) {
            int cancelled = outbox.cancelUnsentRequests(order.getId());
            log.warn("Order {} passed its deadline: legs without a result timed out, {} unsent request(s) cancelled",
                    order.getId(), cancelled);
        }
    }

    private static boolean isDue(Instant dueAt, Instant now) {
        return dueAt != null && !now.isBefore(dueAt);
    }
}
