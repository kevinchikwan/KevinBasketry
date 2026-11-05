package com.basketry.identity.merchantlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.config.InternalApiKey;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The lost-race retry in MerchantLinkService. A concurrent write is simulated
 * deterministically: the first lookup is made to miss a row that is really
 * there, so the write hits the unique constraint exactly as it would if the
 * other write had landed between our check and our insert.
 */
@SpringBootTest(properties = "basketry.internal.api-key=" + MerchantLinkTests.KEY)
@AutoConfigureMockMvc
class MerchantLinkRaceTests {

    private static final String PASSWORD = "Corr3ct-Horse!";
    private static final String INITIAL_PASSWORD = "Init1al-Pass!";

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @BeforeEach
    void clearAccounts() {
        accounts.deleteAll();
    }

    @Test
    void concurrentRegistrationOfSameEmailIsRetriedAsALink() throws Exception {
        // "Registered concurrently": the first findByEmail does not see it yet.
        Account registered = registrationService.registerBuyer("shop@example.com", PASSWORD);
        doReturn(Optional.empty()).doAnswer(realRepository()).when(accounts).findByEmail("shop@example.com");

        link("shop@example.com", 7L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LINKED"))
                .andExpect(jsonPath("$.id").value(registered.getId()));

        verify(accounts, times(2)).findByEmail("shop@example.com");
        assertThat(accounts.count()).isOne();
        assertThat(accounts.findById(registered.getId()).orElseThrow().getMerchantId()).isEqualTo(7L);
    }

    @Test
    void concurrentLinkOfSameMerchantIdIsRetriedAsAConflict() throws Exception {
        link("first@example.com", 7L).andExpect(status().isCreated());
        clearInvocations(accounts);
        // "Linked concurrently": the first findByMerchantId does not see first@'s link yet.
        doReturn(Optional.empty()).doAnswer(realRepository()).when(accounts).findByMerchantId(7L);

        link("second@example.com", 7L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT"));

        verify(accounts, times(2)).findByMerchantId(7L);
        // The first attempt's insert was rolled back.
        assertThat(accounts.findByEmail("second@example.com")).isEmpty();
    }

    @Test
    void concurrentLinkOfSameMerchantIdToAnExistingAccountIsRetriedAsAConflict() throws Exception {
        link("first@example.com", 7L).andExpect(status().isCreated());
        Account second = registrationService.registerBuyer("second@example.com", PASSWORD);
        doReturn(Optional.empty()).doAnswer(realRepository()).when(accounts).findByMerchantId(7L);

        link("second@example.com", 7L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT"));

        assertThat(accounts.findById(second.getId()).orElseThrow().getMerchantId()).isNull();
    }

    /**
     * The spy's own default answer, which forwards to the real repository.
     * (doCallRealMethod cannot be used: the repository is an interface proxy.)
     */
    private Answer<?> realRepository() {
        return mockingDetails(accounts).getMockCreationSettings().getDefaultAnswer();
    }

    private ResultActions link(String email, long merchantId) throws Exception {
        return mockMvc.perform(post("/internal/accounts/merchant-link")
                .header(InternalApiKey.HEADER, MerchantLinkTests.KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "merchantId": %d, "initialPassword": "%s"}
                        """.formatted(email, merchantId, INITIAL_PASSWORD)));
    }
}
