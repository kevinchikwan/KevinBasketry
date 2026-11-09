package com.basketry.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The same key rules as identity-service (DESIGN.md Section 3). */
@ExtendWith(OutputCaptureExtension.class)
class InternalApiKeyTests {

    private static final String KEY = "a-key-of-exactly-thirty-two-char";

    @Test
    void theConfiguredKeyMatchesAndNothingElseDoes() {
        InternalApiKey key = new InternalApiKey(KEY);

        assertThat(key.matches(KEY)).isTrue();
        assertThat(key.matches(KEY + "x")).isFalse();
        assertThat(key.matches(KEY.substring(1))).isFalse();
        assertThat(key.matches("")).isFalse();
        assertThat(key.matches(null)).isFalse();
    }

    /** A Secret often carries a trailing newline; it must neither count nor break the match. */
    @Test
    void surroundingWhitespaceIsStrippedBeforeTheLengthCheckAndTheHash() {
        assertThat(new InternalApiKey("  " + KEY + "\n").matches(KEY)).isTrue();
        assertThatThrownBy(() -> new InternalApiKey(" " + KEY.substring(1) + "\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void aKeyShorterThan32CharactersFailsStartup() {
        assertThatThrownBy(() -> new InternalApiKey(KEY.substring(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("basketry.internal.api-key must be at least 32 characters.");
    }

    @Test
    void noKeyWarnsAndNeverMatches(CapturedOutput output) {
        InternalApiKey key = new InternalApiKey("");

        assertThat(key.matches("")).isFalse();
        assertThat(key.matches(KEY)).isFalse();
        assertThat(output).contains("WARN").contains("every /internal/** request will be refused");
    }
}
