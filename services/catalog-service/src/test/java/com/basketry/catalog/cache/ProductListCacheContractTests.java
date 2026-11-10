package com.basketry.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The contract of the listing cache's store, run against the in-memory fake
 * (default run) and against Redis (postgresTest), so the fake the default run
 * relies on behaves like the real thing.
 */
abstract class ProductListCacheContractTests {

    protected abstract ProductListCache cache();

    /** Mutation: a missing version read as anything but 0. */
    @Test
    void aVersionNeverBumpedIsZeroAndReadingItCreatesNothing() {
        assertThat(cache().version("all")).isZero();
        assertThat(cache().version("all")).isZero();

        cache().bump("all");

        assertThat(cache().version("all")).isEqualTo(1);
    }

    @Test
    void eachBumpAddsOne() {
        cache().bump("m:3");
        cache().bump("m:3");
        cache().bump("m:3");

        assertThat(cache().version("m:3")).isEqualTo(3);
    }

    @Test
    void scopesHaveSeparateVersions() {
        cache().bump("m:3");

        assertThat(cache().version("m:4")).isZero();
        assertThat(cache().version("all")).isZero();
    }

    @Test
    void aStoredPageIsReadBackAndAMissingOneIsNull() {
        cache().put("catalog:v1:list:all:0:c*:p=0:s=20", "{\"page\":1}", Duration.ofSeconds(60));

        assertThat(cache().get("catalog:v1:list:all:0:c*:p=0:s=20")).isEqualTo("{\"page\":1}");
        assertThat(cache().get("catalog:v1:list:all:0:c*:p=1:s=20")).isNull();
    }

    @Test
    void putReplacesThePageThere() {
        cache().put("k", "old", Duration.ofSeconds(60));
        cache().put("k", "new", Duration.ofSeconds(60));

        assertThat(cache().get("k")).isEqualTo("new");
    }
}
