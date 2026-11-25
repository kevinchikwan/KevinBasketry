package com.basketry.promotion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The same key rules as identity-service (DESIGN.md Section 3); a copy of inventory-service's tests. */
@ExtendWith(OutputCaptureExtension.class)
class InternalApiKeyTests {

    private static final String KEY = "a-key-of-exactly-thirty-two-char";

    /** Mutation: {@code matches} true for any non-null key. */
    @Test
    void theConfiguredKeyMatchesAndNothingElseDoes() {
        InternalApiKey key = new InternalApiKey(KEY);

        assertThat(key.matches(KEY)).isTrue();
        assertThat(key.matches(KEY + "x")).isFalse();
        assertThat(key.matches(KEY.substring(1))).isFalse();
        assertThat(key.matches("")).isFalse();
        assertThat(key.matches(null)).isFalse();
    }

    /** A Secret often carries a trailing newline; it must neither count nor break the match. Mutation: no strip(). */
    @Test
    void surroundingWhitespaceIsStrippedBeforeTheLengthCheckAndTheHash() {
        assertThat(new InternalApiKey("  " + KEY + "\n").matches(KEY)).isTrue();
        assertThatThrownBy(() -> new InternalApiKey(" " + KEY.substring(1) + "\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    /** Mutation: {@code key.length() < MIN_LENGTH - 1} (31 characters accepted). */
    @Test
    void aKeyShorterThan32CharactersFailsStartup() {
        assertThatThrownBy(() -> new InternalApiKey(KEY.substring(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("basketry.internal.api-key must be at least 32 characters.");
    }

    /** Mutation: an unset key kept as the digest of "" (an empty header then matches). */
    @Test
    void noKeyWarnsAndNeverMatches(CapturedOutput output) {
        InternalApiKey key = new InternalApiKey("");

        assertThat(key.matches("")).isFalse();
        assertThat(key.matches(KEY)).isFalse();
        assertThat(output).contains("WARN").contains("every /internal/** request will be refused");
    }

    /** Mutation: toString() with the key. */
    @Test
    void theKeyIsNeverInToString() {
        assertThat(new InternalApiKey(KEY).toString()).isEqualTo("InternalApiKey[***]");
        assertThat(new InternalApiKey("").toString()).isEqualTo("InternalApiKey[unset]");
    }
}
