package com.basketry.inventory.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.inventory.counter.StockCounterGuard.Take;
import com.basketry.inventory.counter.StockCounterProperties.Reconciliation;
import com.basketry.inventory.support.InMemoryStockCounter;
import com.basketry.inventory.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Row 4 of the Day 10 review: Redis failures become "unavailable", and Redis is then skipped for a while. */
@ExtendWith(OutputCaptureExtension.class)
class StockCounterGuardTests {

    private final InMemoryStockCounter fake = new InMemoryStockCounter();
    private final MutableClock clock = new MutableClock();
    private final StockCounterGuard guard = new StockCounterGuard(fake, new StockCounterProperties(
            Duration.ofSeconds(10), new Reconciliation(false, Duration.ofMinutes(5), 500)), clock);

    @Test
    void afterAFailureRedisIsSkippedForTheWindowThenTriedAgain(CapturedOutput output) {
        fake.overwrite(42, 5);
        fake.setUnavailable(true);

        assertThat(guard.take(42, 1)).isEqualTo(Take.UNAVAILABLE);
        fake.setUnavailable(false);
        int callsAfterFailure = fake.calls();

        clock.advance(Duration.ofSeconds(9));
        assertThat(guard.take(42, 1)).isEqualTo(Take.UNAVAILABLE);
        assertThat(guard.addIfLoaded(42, 1)).isFalse();
        assertThat(guard.overwrite(42, 1)).isFalse();
        assertThat(fake.calls()).isEqualTo(callsAfterFailure);

        clock.advance(Duration.ofSeconds(1));
        assertThat(guard.take(42, 1)).isEqualTo(Take.TAKEN);
        assertThat(output).contains("WARN").contains("The stock counters (Redis) are unavailable")
                .contains("The stock counters (Redis) are reachable again");
    }

    /** The reply was lost after Redis took the stock: still never reported as taken. */
    @Test
    void aLostReplyIsUnavailableNeverTaken() {
        fake.overwrite(42, 5);
        fake.loseNextReply();

        assertThat(guard.take(42, 1)).isEqualTo(Take.UNAVAILABLE);
        assertThat(fake.current(42)).hasValue(4);
    }

    @Test
    void anOutageIsWarnedAboutOnceNotOnEveryCall(CapturedOutput output) {
        fake.setUnavailable(true);

        for (int i = 0; i < 3; i++) {
            guard.take(42, 1);
            clock.advance(Duration.ofSeconds(11));
        }

        assertThat(output.toString().split("are unavailable", -1)).hasSize(2);
    }
}
