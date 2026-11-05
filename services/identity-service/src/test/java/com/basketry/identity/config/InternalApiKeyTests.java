package com.basketry.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * How the key is configured. The Spring context here has no key set, as in
 * local dev without BASKETRY_INTERNAL_API_KEY.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalApiKeyTests {

    private static final String VALID = "k".repeat(32);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withNoKeyConfiguredTheServiceStartsAndRefusesEveryInternalCall() throws Exception {
        for (String presented : new String[] {"", "anything", VALID}) {
            mockMvc.perform(post("/internal/accounts/merchant-link")
                            .header(InternalApiKey.HEADER, presented)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "m@example.com", "merchantId": 7, "initialPassword": "Init1al-Pass!"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unsetOrBlankKeyMatchesNothing() {
        assertThat(new InternalApiKey("").matches("")).isFalse();
        assertThat(new InternalApiKey("   ").matches("   ")).isFalse();
        assertThat(new InternalApiKey(null).matches(null)).isFalse();
    }

    @Test
    void keyShorterThan32CharactersFailsStartup() {
        assertThatThrownBy(() -> new InternalApiKey("k".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void keyOf32CharactersIsAccepted() {
        assertThat(new InternalApiKey(VALID).matches(VALID)).isTrue();
    }

    @Test
    void onlyTheExactKeyMatches() {
        InternalApiKey key = new InternalApiKey(VALID);

        assertThat(key.matches(null)).isFalse();
        assertThat(key.matches("")).isFalse();
        assertThat(key.matches(VALID + "k")).isFalse();
        assertThat(key.matches(VALID.substring(1))).isFalse();
        assertThat(key.matches(VALID.toUpperCase())).isFalse();
    }
}
