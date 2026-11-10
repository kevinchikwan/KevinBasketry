package com.basketry.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.catalog.support.InMemoryProductListCache;
import com.basketry.catalog.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Row 8 of the Day 12 review: what one invalidation bumps, and when it may run. */
@ExtendWith(OutputCaptureExtension.class)
class ListingInvalidatorTests {

    private final MutableClock clock = new MutableClock();
    private final InMemoryProductListCache fake = new InMemoryProductListCache(clock);
    private final ListingInvalidator invalidator = new ListingInvalidator(new ProductListCacheGuard(fake,
            new CacheProperties(Duration.ofSeconds(60), Duration.ofSeconds(10)), clock));

    @AfterEach
    void endTheFakeTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** Mutation: bump only one of the two scopes. */
    @Test
    void itBumpsTheAllScopeAndTheMerchantsOwn() {
        invalidator.merchantChanged(3);

        assertThat(fake.version("all")).isEqualTo(1);
        assertThat(fake.version("m:3")).isEqualTo(1);
        assertThat(fake.version("m:4")).isZero();
    }

    /** Mutation: drop the check (an invalidation moved into a transaction would go unnoticed). */
    @Test
    void itRefusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> invalidator.merchantChanged(3)).isInstanceOf(IllegalStateException.class);
        assertThat(fake.bumps()).isZero();
    }

    @Test
    void aFailedInvalidationIsWarnedAbout(CapturedOutput output) {
        fake.setUnavailable(true);

        invalidator.merchantChanged(3);

        assertThat(output).contains("WARN").contains("Could not invalidate the cached listings of merchant 3");
    }
}
