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
import com.basketry.order.saga.SagaResult.Charged;
import com.basketry.order.saga.SagaResult.Declined;
import com.basketry.order.saga.SagaResult.Refunded;
import com.basketry.order.saga.SagaResult.Released;
import com.basketry.order.saga.SagaResult.ReservationFailed;
import com.basketry.order.saga.SagaResult.Reserved;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one result to its leg, by the leg's state, then decides (DESIGN.md
 * Section 2, order-service, results by leg state). Each call is one
 * transaction that first locks the order's row, so the results of one order
 * are handled one at a time and exactly one of them decides. The listeners
 * call this outside any transaction.
 *
 * <table>
 *   <caption>The table of DESIGN.md: "drop" changes nothing</caption>
 *   <tr><th>Result</th><th>REQUESTED</th><th>TIMED_OUT</th><th>Other</th></tr>
 *   <tr><td>charged</td><td>CHARGED; refund at once if FAILING</td><td>refund; reopen a FAILED order</td>
 *       <td>drop</td></tr>
 *   <tr><td>declined</td><td>DECLINED</td><td>drop</td><td>drop</td></tr>
 *   <tr><td>refunded</td><td>cannot happen (WARN)</td><td>cannot happen (WARN)</td>
 *       <td>REFUND_REQUESTED or STUCK: REFUNDED; else drop</td></tr>
 *   <tr><td>reserved</td><td>RESERVED; release at once if FAILING</td><td>drop</td><td>drop</td></tr>
 *   <tr><td>reservation-failed</td><td>FAILED</td><td>drop</td><td>drop</td></tr>
 *   <tr><td>released</td><td>cannot happen (WARN)</td><td>RELEASED</td>
 *       <td>RELEASE_REQUESTED or STUCK: RELEASED; else drop</td></tr>
 * </table>
 *
 * <p>A result for an order or a leg this service does not have (data loss) is
 * logged as a WARN and skipped.
 */
@Service
public class SagaService {

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final CustomerOrderRepository orders;
    private final MerchantChargeRepository charges;
    private final OrderLineRepository lines;
    private final OrderOutbox outbox;
    private final SagaDecision decision;
    private final Clock clock;
    private final SagaProperties properties;

    public SagaService(CustomerOrderRepository orders, MerchantChargeRepository charges, OrderLineRepository lines,
                       OrderOutbox outbox, SagaDecision decision, Clock clock, SagaProperties properties) {
        this.orders = orders;
        this.charges = charges;
        this.lines = lines;
        this.outbox = outbox;
        this.decision = decision;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public void apply(SagaResult result) {
        Optional<CustomerOrder> locked = orders.findByIdForUpdate(result.orderId());
        if (locked.isEmpty()) {
            log.warn("A result for order {}, which order-service does not have: skipped", result.orderId());
            return;
        }
        CustomerOrder order = locked.get();
        List<MerchantCharge> orderCharges = charges.findByOrderIdOrderByMerchantIdAsc(order.getId());
        List<OrderLine> orderLines = lines.findByOrderIdOrderByProductIdAsc(order.getId());
        boolean applied = switch (result) {
            case Charged charged -> leg(orderCharges, charged.merchantId(), result)
                    .map(leg -> onCharged(order, leg, charged)).orElse(false);
            case Declined declined -> leg(orderCharges, declined.merchantId(), result)
                    .map(leg -> onDeclined(leg, declined)).orElse(false);
            case Refunded refunded -> leg(orderCharges, refunded.merchantId(), result)
                    .map(this::onRefunded).orElse(false);
            case Reserved reserved -> line(orderLines, reserved.productId(), result)
                    .map(line -> onReserved(order, line)).orElse(false);
            case ReservationFailed failed -> line(orderLines, failed.productId(), result)
                    .map(line -> onReservationFailed(line, failed)).orElse(false);
            case Released released -> line(orderLines, released.productId(), result)
                    .map(this::onReleased).orElse(false);
        };
        if (applied) {
            decision.decide(order, orderCharges, orderLines);
        } else {
            log.debug("Dropped {}: a repeat, or nothing left to do for its leg", result);
        }
    }

    private boolean onCharged(CustomerOrder order, MerchantCharge leg, Charged charged) {
        if (leg.getStatus() == ChargeStatus.REQUESTED) {
            if (order.getStatus() == OrderStatus.FAILING) {
                requestRefund(order, leg, charged.transactionRef());
            } else {
                leg.charged(charged.transactionRef());
            }
            return true;
        }
        if (leg.getStatus() == ChargeStatus.TIMED_OUT) {
            // Money taken after the order gave up on it: always refunded.
            if (order.getStatus() == OrderStatus.FAILED) {
                order.reopen();
            }
            requestRefund(order, leg, charged.transactionRef());
            return true;
        }
        return false;
    }

    private void requestRefund(CustomerOrder order, MerchantCharge leg, String transactionRef) {
        leg.refundRequested(transactionRef, clock.instant().plus(properties.compensation().resendInterval()));
        outbox.recordRefund(order.getId(), leg.getMerchantId(), transactionRef);
    }

    private static boolean onDeclined(MerchantCharge leg, Declined declined) {
        if (leg.getStatus() == ChargeStatus.REQUESTED) {
            leg.declined(declined.reasonCode());
            return true;
        }
        return false;
    }

    private boolean onRefunded(MerchantCharge leg) {
        if (leg.getStatus() == ChargeStatus.REFUND_REQUESTED || leg.getStatus() == ChargeStatus.STUCK) {
            leg.refunded();
            return true;
        }
        if (leg.getStatus() == ChargeStatus.REQUESTED || leg.getStatus() == ChargeStatus.TIMED_OUT) {
            log.warn("A refunded result for order {}, merchant {}, whose refund was never requested: dropped",
                    leg.getOrderId(), leg.getMerchantId());
        }
        return false;
    }

    private boolean onReserved(CustomerOrder order, OrderLine line) {
        if (line.getStatus() != LineStatus.REQUESTED) {
            // TIMED_OUT: its release is already requested. Otherwise a repeat, or a re-publish after a release.
            return false;
        }
        if (order.getStatus() == OrderStatus.FAILING) {
            line.releaseRequested(clock.instant().plus(properties.compensation().resendInterval()));
            outbox.recordRelease(order.getId(), line.getProductId(), line.getQuantity());
        } else {
            line.reserved();
        }
        return true;
    }

    private static boolean onReservationFailed(OrderLine line, ReservationFailed failed) {
        if (line.getStatus() == LineStatus.REQUESTED) {
            line.reservationFailed(failed.reasonCode());
            return true;
        }
        return false;
    }

    private boolean onReleased(OrderLine line) {
        LineStatus status = line.getStatus();
        if (status == LineStatus.TIMED_OUT || status == LineStatus.RELEASE_REQUESTED || status == LineStatus.STUCK) {
            line.released();
            return true;
        }
        if (status == LineStatus.REQUESTED) {
            log.warn("A released result for order {}, product {}, whose release was never requested: dropped",
                    line.getOrderId(), line.getProductId());
        }
        return false;
    }

    private static Optional<MerchantCharge> leg(List<MerchantCharge> legs, long merchantId, SagaResult result) {
        Optional<MerchantCharge> leg = legs.stream().filter(charge -> charge.getMerchantId() == merchantId).findFirst();
        if (leg.isEmpty()) {
            log.warn("A result for order {} names merchant {}, which has no charge leg in it: skipped",
                    result.orderId(), merchantId);
        }
        return leg;
    }

    private static Optional<OrderLine> line(List<OrderLine> lines, long productId, SagaResult result) {
        Optional<OrderLine> line = lines.stream().filter(candidate -> candidate.getProductId() == productId).findFirst();
        if (line.isEmpty()) {
            log.warn("A result for order {} names product {}, which has no line in it: skipped",
                    result.orderId(), productId);
        }
        return line;
    }
}
