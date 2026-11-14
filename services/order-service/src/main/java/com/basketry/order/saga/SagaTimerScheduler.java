package com.basketry.order.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the timer every basketry.order.timer-interval; fixedDelay means passes never overlap. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "basketry.order.timer.enabled", havingValue = "true")
class SagaTimerScheduler {

    private static final Logger log = LoggerFactory.getLogger(SagaTimerScheduler.class);

    private final SagaTimer timer;

    SagaTimerScheduler(SagaTimer timer) {
        this.timer = timer;
    }

    @Scheduled(fixedDelayString = "${basketry.order.timer-interval}")
    void tick() {
        try {
            timer.pass();
        } catch (RuntimeException failed) {
            // For example the database: the next pass tries again.
            log.warn("Saga timer pass failed: {}", failed.toString());
        }
    }
}
