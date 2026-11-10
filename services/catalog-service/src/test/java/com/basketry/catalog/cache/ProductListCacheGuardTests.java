package com.basketry.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.support.InMemoryProductListCache;
import com.basketry.catalog.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Row 8 of the Day 12 review: Redis failures become "unavailable", and Redis is then skipped for a while. */
@ExtendWith(OutputCaptureExtension.class)
class ProductListCacheGuardTests {

    private final MutableClock clock = new MutableClock();
    private final InMemoryProductListCache fake = new InMemoryProductListCache(clock);
    private final ProductListCacheGuard guard = new ProductListCacheGuard(fake,
            new CacheProperties(Duration.ofSeconds(60), Duration.ofSeconds(10)), clock);

    /** Mutation: no skip window (every listing would wait for a timeout while Redis is down). */
    @Test
    void afterAFailureRedisIsSkippedForTheWindowThenTriedAgain(CapturedOutput output) {
        fake.setUnavailable(true);
        assertThat(guard.version("all")).isEmpty();
        fake.setUnavailable(false);

        clock.advance(Duration.ofSeconds(9));
        assertThat(guard.version("all")).isEmpty();
        assertThat(guard.bump("all")).isFalse();
        assertThat(guard.put("k", "page")).isFalse();
        assertThat(guard.get("k")).isEmpty();
        assertThat(fake.bumps()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(guard.version("all")).hasValue(0);
        assertThat(output).contains("WARN").contains("The listing cache (Redis) is unavailable")
                .contains("The listing cache (Redis) is reachable again");
    }

    /** Mutation: store pages without the configured TTL. */
    @Test
    void pagesAreStoredWithTheConfiguredTtl() {
        guard.put("k", "page");

        clock.advance(Duration.ofSeconds(59));
        assertThat(guard.get("k")).hasValue("page");
        clock.advance(Duration.ofSeconds(1));
        assertThat(guard.get("k")).isEmpty();
    }

    @Test
    void anOutageIsWarnedAboutOnceNotOnEveryCall(CapturedOutput output) {
        fake.setUnavailable(true);

        for (int i = 0; i < 3; i++) {
            guard.version("all");
            clock.advance(Duration.ofSeconds(11));
        }

        assertThat(output.toString().split("is unavailable", -1)).hasSize(2);
    }
}
