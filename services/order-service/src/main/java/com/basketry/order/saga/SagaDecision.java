package com.basketry.order.saga;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderStatus;
import com.basketry.order.outbox.OrderEvents.Reason;
import com.basketry.order.outbox.OrderOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The decision (DESIGN.md Section 2, order-service), made in the transaction
 * that changed a leg, under the order's row lock, after every change:
 *
 * <ul>
 *   <li>PENDING and every leg positive: SUCCEEDED, with order.succeeded.</li>
 *   <li>PENDING and a leg negative: FAILING; every CHARGED leg gets its refund
 *       and every RESERVED line its release. Legs still REQUESTED keep waiting
 *       for their result or the deadline.</li>
 *   <li>FAILING and every leg settled: FAILED, with order.failed (again after a
 *       reopen).</li>
 *   <li>needsAttention is true exactly while a leg is STUCK.</li>
 * </ul>
 */
@Component
public class SagaDecision {

    private final OrderOutbox outbox;
    private final Clock clock;
    private final SagaProperties properties;

    public SagaDecision(OrderOutbox outbox, Clock clock, SagaProperties properties) {
        this.outbox = outbox;
        this.clock = clock;
        this.properties = properties;
    }

    /** Fails if no transaction is open: the caller holds the order's row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void decide(CustomerOrder order, List<MerchantCharge> charges, List<OrderLine> lines) {
        Instant now = clock.instant();
        if (order.getStatus() == OrderStatus.PENDING) {
            if (charges.stream().anyMatch(MerchantCharge::negative) || lines.stream().anyMatch(OrderLine::negative)) {
                order.markFailing(now);
                compensatePositiveLegs(order, charges, lines, now);
            } else if (charges.stream().allMatch(charge -> charge.getStatus() == ChargeStatus.CHARGED)
                    && lines.stream().allMatch(line -> line.getStatus() == LineStatus.RESERVED)) {
                order.markSucceeded(now);
                outbox.recordSucceeded(order, charges, lines);
            }
        }
        if (order.getStatus() == OrderStatus.FAILING
                && charges.stream().allMatch(MerchantCharge::settled) && lines.stream().allMatch(OrderLine::settled)) {
            order.markFailed(now);
            outbox.recordFailed(order, reasons(charges, lines));
        }
        order.setNeedsAttention(charges.stream().anyMatch(charge -> charge.getStatus() == ChargeStatus.STUCK)
                || lines.stream().anyMatch(line -> line.getStatus() == LineStatus.STUCK));
    }

    private void compensatePositiveLegs(CustomerOrder order, List<MerchantCharge> charges, List<OrderLine> lines,
                                        Instant now) {
        Instant due = now.plus(properties.compensation().resendInterval());
        for (MerchantCharge charge : charges) {
            if (charge.getStatus() == ChargeStatus.CHARGED) {
                charge.refundRequested(charge.getTransactionRef(), due);
                outbox.recordRefund(order.getId(), charge.getMerchantId(), charge.getTransactionRef());
            }
        }
        for (OrderLine line : lines) {
            if (line.getStatus() == LineStatus.RESERVED) {
                line.releaseRequested(due);
                outbox.recordRelease(order.getId(), line.getProductId(), line.getQuantity());
            }
        }
    }

    /** Every leg that failed the order, the charges first, each in key order. */
    private static List<Reason> reasons(List<MerchantCharge> charges, List<OrderLine> lines) {
        List<Reason> reasons = new ArrayList<>();
        for (MerchantCharge charge : charges) {
            if (charge.getReasonCode() != null) {
                reasons.add(Reason.charge(charge.getMerchantId(), charge.getReasonCode()));
            }
        }
        for (OrderLine line : lines) {
            if (line.getReasonCode() != null) {
                reasons.add(Reason.reserve(line.getProductId(), line.getReasonCode()));
            }
        }
        return reasons;
    }
}
