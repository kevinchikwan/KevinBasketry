package com.basketry.merchant.config;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.support.MerchantServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A spring.jackson setting of the application reaches the request body
 * through QuietBodiesConfig's converter: here trailing tokens are allowed,
 * which Jackson refuses by default, so the same body that is a 400 in every
 * other test is read.
 */
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-trailing-tokens=false")
class QuietBodiesJacksonSettingTests extends MerchantServiceTestBase {

    /** Mutation: the converter given its own JsonMapper (Jackson's defaults, not the application's settings). */
    @Test
    void theApplicationsJacksonSettingsApplyToTheBody() throws Exception {
        Merchant merchant = saveLinked("setting@example.com");

        patchAsAdmin(merchant, "{\"active\":true} x").andExpect(status().isOk());
    }
}
