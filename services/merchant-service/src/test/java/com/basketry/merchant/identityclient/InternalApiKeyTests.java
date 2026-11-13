package com.basketry.merchant.identityclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The shared key, both ways: same strip and minimum-length rules as identity-service, and the incoming check. */
class InternalApiKeyTests {

    private static final String KEY = "test-only-internal-key-not-a-secret-000";

    @Test
    void surroundingWhitespaceIsStripped() {
        InternalApiKey apiKey = new InternalApiKey("  " + KEY + "\n");

        assertThat(apiKey.isConfigured()).isTrue();
        assertThat(apiKey.value()).isEqualTo(KEY);
    }

    @Test
    void unsetOrBlankMeansNotConfigured() {
        for (String unset : new String[] {null, "", "  \n"}) {
            InternalApiKey apiKey = new InternalApiKey(unset);
            assertThat(apiKey.isConfigured()).isFalse();
            assertThatThrownBy(apiKey::value).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shorterThan32CharactersFailsStartup() {
        assertThatThrownBy(() -> new InternalApiKey("x".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
        // Whitespace does not count toward the minimum.
        assertThatThrownBy(() -> new InternalApiKey(" " + "x".repeat(31) + "\n"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new InternalApiKey("x".repeat(32)).isConfigured()).isTrue();
    }

    /** Mutation: {@code matches} true for any non-null key (accepts KEY + "x"). */
    @Test
    void matchesOnlyTheConfiguredKey() {
        InternalApiKey apiKey = new InternalApiKey(KEY);

        assertThat(apiKey.matches(KEY)).isTrue();
        assertThat(apiKey.matches(KEY + "x")).isFalse();
        assertThat(apiKey.matches(KEY.substring(1))).isFalse();
        assertThat(apiKey.matches("")).isFalse();
        assertThat(apiKey.matches(null)).isFalse();
    }

    /**
     * A Secret's trailing newline is not part of the key that must be
     * presented. Mutation: the digest taken of the configured value before
     * stripping.
     */
    @Test
    void theStrippedKeyIsTheOneThatMatches() {
        InternalApiKey apiKey = new InternalApiKey("  " + KEY + "\n");

        assertThat(apiKey.matches(KEY)).isTrue();
        assertThat(apiKey.matches("  " + KEY + "\n")).isFalse();
    }

    /** Mutation: an unset key compared against the digest of "" (an empty header then matches). */
    @Test
    void anUnsetKeyNeverMatches() {
        InternalApiKey apiKey = new InternalApiKey("");

        assertThat(apiKey.matches("")).isFalse();
        assertThat(apiKey.matches(null)).isFalse();
        assertThat(apiKey.matches(KEY)).isFalse();
    }

    /** Mutation: the old WARN, which does not say internal calls are refused. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anUnsetKeyWarnsThatOnboardingAndInternalCallsAreRefused(CapturedOutput output) {
        new InternalApiKey("");

        assertThat(output).contains("WARN")
                .contains("merchant onboarding will be refused")
                .contains("every /internal/** request will be refused");
    }

    @Test
    void toStringNeverShowsTheKey() {
        assertThat(new InternalApiKey(KEY).toString()).doesNotContain(KEY);
    }

    @Test
    void merchantLinkCallToStringMasksTheInitialPassword() {
        assertThat(new MerchantLinkCall("a@example.com", 3, "Init1al-Pass!").toString())
                .doesNotContain("Init1al-Pass!")
                .contains("initialPassword=***");
    }
}
