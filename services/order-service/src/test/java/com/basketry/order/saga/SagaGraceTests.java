package com.basketry.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.order.OrderStatus;
import com.basketry.order.support.SagaTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The startup grace (DESIGN.md Section 2, order-service, the timer): for 60 s
 * after the service starts, a pass does nothing, even for an order past its
 * deadline, so results still queued in Kafka after a restart are read first.
 * The service "starts" at T0 here; the deadline is shortened to 10 s so it
 * passes inside the grace. Also the batch: a pass handles at most batch-size
 * orders.
 */
@TestPropertySource(properties = {
        "basketry.order.startup-grace=60s",
        "basketry.order.saga-deadline=10s",
        "basketry.order.timer.batch-size=1"})
class SagaGraceTests extends SagaTestBase {

    /** Mutation: the grace check removed. */
    @Test
    void nothingHappensDuringTheGrace() {
        long id = order();

        assertThat(passAfter(Duration.ofSeconds(59))).isZero();
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.PENDING);

        assertThat(passAfter(Duration.ofSeconds(1))).isEqualTo(1);
        assertThat(orderOf(id).getStatus()).isEqualTo(OrderStatus.FAILING);
    }

    /**
     * One order a pass here, the lowest id first, although two are due for
     * different reasons (one with a release due again, one past its deadline),
     * so the timer's queries find two. Mutation: the batch check removed (both
     * handled in one pass).
     */
    @Test
    void aPassHandlesAtMostOneBatch() {
        long releaseDue = order();
        clock.advance(Duration.ofSeconds(60));
        assertThat(timer.pass()).isEqualTo(1);
        assertThat(orderOf(releaseDue).getStatus()).isEqualTo(OrderStatus.FAILING);
        long pastDeadline = order();
        clock.advance(Duration.ofMinutes(1));

        assertThat(timer.pass()).isEqualTo(1);
        assertThat(lineOf(releaseDue, PRODUCT_A).getResends()).isEqualTo(1);
        assertThat(orderOf(pastDeadline).getStatus()).isEqualTo(OrderStatus.PENDING);

        assertThat(timer.pass()).isEqualTo(1);
        assertThat(orderOf(pastDeadline).getStatus()).isEqualTo(OrderStatus.FAILING);
    }
}
