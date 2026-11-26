package com.basketry.merchant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.merchant.AccountLinkRequest;
import com.basketry.merchant.merchant.CreateMerchantRequest;
import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.merchant.UpdateMerchantRequest;
import com.basketry.merchant.support.MerchantServiceTestBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * QuietBodiesConfig's converter is the one Spring MVC reads JSON bodies with,
 * and it reads them with the application's own JsonMapper, so every strict
 * JSON rule still rejects what it rejected before.
 */
class QuietBodiesConverterTests extends MerchantServiceTestBase {

    @Autowired
    private RequestMappingHandlerAdapter mvcAdapter;

    @Autowired
    private JsonMapper applicationMapper;

    /**
     * For each body type, the first converter MVC finds that reads it as JSON
     * (the one that reads it) is ours, with the application's JsonMapper bean.
     * Spring Data's web support adds two more Jackson converters after it (one
     * for projection interfaces, one plain), which are never reached for these
     * types. Mutations: the converter given its own JsonMapper; the converter
     * bean removed (Boot's own converter first).
     */
    @ParameterizedTest
    @ValueSource(classes = {CreateMerchantRequest.class, AccountLinkRequest.class, UpdateMerchantRequest.class})
    void mvcReadsEachBodyWithOurConverterAndTheApplicationsJsonMapper(Class<?> body) {
        HttpMessageConverter<?> reader = mvcAdapter.getMessageConverters().stream()
                .filter(converter -> converter.canRead(body, MediaType.APPLICATION_JSON)).findFirst().orElseThrow();

        assertThat(reader.getClass().getEnclosingClass()).isEqualTo(QuietBodiesConfig.class);
        assertThat(((JacksonJsonHttpMessageConverter) reader).getMapper()).isSameAs(applicationMapper);
    }

    /**
     * Each strict rule still refuses its body, with the same answer:
     * kycVerified and active take only a JSON boolean, commissionRate only a
     * JSON number, a string field no object, and nothing may follow the body.
     * Mutation: the strict boolean reader accepting the string "true".
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"kycVerified\":\"true\"}", "{\"kycVerified\":1}", "{\"active\":\"false\"}",
            "{\"active\":0}", "{\"commissionRate\":\"5.00\"}", "{\"commissionRate\":true}", "{\"active\":true} x"})
    void everyStrictJsonRuleStillRejects(String body) throws Exception {
        Merchant merchant = saveLinked("strict@example.com");

        patchAsAdmin(merchant, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
    }
}
