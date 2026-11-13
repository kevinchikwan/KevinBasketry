package com.basketry.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The internal API key and the fingerprint key: the same rules as every service's internal key. */
@ExtendWith(OutputCaptureExtension.class)
class KeysTests {

    private static final String KEY = "a-key-of-exactly-thirty-two-char";

    /** A Secret's trailing newline neither counts nor is sent. Mutation: no strip(). */
    @Test
    void surroundingWhitespaceIsStripped() {
        assertThat(new InternalApiKey("  " + KEY + "\n").value()).hasValue(KEY);
        assertThat(new FingerprintKey("  " + KEY + "\n").hmac("x"))
                .isEqualTo(new FingerprintKey(KEY).hmac("x"));
    }

    /** Mutation: MIN_LENGTH 31 (a 31-character key accepted). */
    @Test
    void aKeyShorterThan32CharactersFailsStartup() {
        assertThatThrownBy(() -> new InternalApiKey(KEY.substring(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("basketry.internal.api-key must be at least 32 characters.");
        assertThatThrownBy(() -> new FingerprintKey(" " + KEY.substring(1) + "\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("basketry.order.fingerprint-key must be at least 32 characters.");
    }

    /** Unset: a WARN saying checkouts will be refused, and nothing to use. Mutation: no WARN. */
    @Test
    void anUnsetKeyWarnsAndGivesNothing(CapturedOutput output) {
        assertThat(new InternalApiKey("").value()).isEmpty();
        assertThat(new FingerprintKey(null).hmac("x")).isEmpty();

        assertThat(output).contains("WARN")
                .contains("basketry.internal.api-key is not set (env BASKETRY_INTERNAL_API_KEY); "
                        + "every checkout will be refused.")
                .contains("basketry.order.fingerprint-key is not set (env BASKETRY_ORDER_FINGERPRINT_KEY); "
                        + "every checkout will be refused.");
    }

    /** Mutation: the record-style toString with the key. */
    @Test
    void neitherKeyIsEverShown() {
        assertThat(new InternalApiKey(KEY).toString()).doesNotContain(KEY);
        assertThat(new FingerprintKey(KEY).toString()).doesNotContain(KEY);
    }
}
