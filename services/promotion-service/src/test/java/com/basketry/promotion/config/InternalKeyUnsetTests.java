package com.basketry.promotion.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.support.WebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * With no key configured (local dev), the service starts and refuses every
 * internal call, whatever is sent: the same bare 401 as no key or a wrong key,
 * as merchant-service. Mutation: an unset key kept as the digest of "" (an
 * empty header then matches).
 */
@SpringBootTest(properties = "basketry.internal.api-key=")
@AutoConfigureMockMvc
class InternalKeyUnsetTests {

    @Autowired
    private MockMvc mvc;

    private static MockHttpServletRequestBuilder priceRequest() {
        return post(WebTestBase.PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":25.50}]}");
    }

    @Test
    void everyInternalCallIsABare401() throws Exception {
        mvc.perform(priceRequest()).andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(priceRequest().header(InternalApiKey.HEADER, ""))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(priceRequest().header(InternalApiKey.HEADER, WebTestBase.API_KEY))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }
}
