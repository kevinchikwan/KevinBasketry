package com.basketry.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Row 8 of the Day 12 review: the key design. */
class CacheKeysTests {

    @Test
    void keysCarryTheFormatTheScopeAndItsVersion() {
        assertThat(CacheKeys.versionKey(CacheKeys.ALL)).isEqualTo("catalog:v2:ver:all");
        assertThat(CacheKeys.versionKey(CacheKeys.merchantScope(3))).isEqualTo("catalog:v2:ver:m:3");
        assertThat(CacheKeys.pageKey("m:3", 7, Optional.empty(), 2, 20)).isEqualTo("catalog:v2:list:m:3:7:c*:p=2:s=20");
        assertThat(CacheKeys.scopeOf(OptionalLong.of(3))).isEqualTo("m:3");
        assertThat(CacheKeys.scopeOf(OptionalLong.empty())).isEqualTo("all");
    }

    /**
     * A category is free text. Mutation: put it into the key unencoded (the
     * category "c*" would share the key of "no category", and one with ":p=0"
     * could imitate another page).
     */
    @Test
    void aCategoryCannotImitateAnotherKey() {
        assertThat(CacheKeys.pageKey("all", 0, Optional.of("c*"), 0, 20))
                .isNotEqualTo(CacheKeys.pageKey("all", 0, Optional.empty(), 0, 20));
        assertThat(CacheKeys.pageKey("all", 0, Optional.of("Food & Drink: UK"), 0, 20))
                .isEqualTo("catalog:v2:list:all:0:c=Food+%26+Drink%3A+UK:p=0:s=20");
    }
}
