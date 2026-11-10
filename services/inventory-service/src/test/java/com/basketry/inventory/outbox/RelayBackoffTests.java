package com.basketry.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RelayBackoffTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final RelayBackoff backoff = new RelayBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60));

    /** The wait that follows one more failure at {@code NOW}, in whole seconds. */
    private long waitAfterFailure() {
        backoff.failed(NOW);
        long seconds = 0;
        while (!backoff.isReady(NOW.plusSeconds(seconds))) {
            seconds++;
        }
        return seconds;
    }

    @Test
    void readyAtFirst() {
        assertThat(backoff.isReady(NOW)).isTrue();
    }

    @Test
    void waitsDoubleUpToTheMaximum() {
        assertThat(new long[] {
                waitAfterFailure(), waitAfterFailure(), waitAfterFailure(), waitAfterFailure(),
                waitAfterFailure(), waitAfterFailure(), waitAfterFailure(), waitAfterFailure()})
                .containsExactly(1, 2, 4, 8, 16, 32, 60, 60);
    }

    @Test
    void notReadyDuringTheWait() {
        backoff.failed(NOW);

        assertThat(backoff.isReady(NOW.plusMillis(999))).isFalse();
        assertThat(backoff.isReady(NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void aSuccessResetsIt() {
        waitAfterFailure();
        waitAfterFailure();

        backoff.succeeded();

        assertThat(backoff.isReady(NOW)).isTrue();
        assertThat(waitAfterFailure()).isEqualTo(1);
    }
}
