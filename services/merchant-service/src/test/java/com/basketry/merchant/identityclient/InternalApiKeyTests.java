package com.basketry.merchant.identityclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The caller side of the shared key: same strip and minimum-length rules as identity-service. */
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
