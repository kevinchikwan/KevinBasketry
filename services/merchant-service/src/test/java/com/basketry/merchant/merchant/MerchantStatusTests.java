package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.outbox.MerchantStatusOutbox;
import com.basketry.merchant.outbox.OutboxEvent;
import com.basketry.merchant.outbox.OutboxStatus;
import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PATCH /merchants/{code} with {@code active} (FR-A3): the 409 while PENDING,
 * the no-op, and the outbox row that shares the PATCH's transaction.
 */
class MerchantStatusTests extends MerchantServiceTestBase {

    @Autowired
    private MerchantUpdateService updates;

    @Autowired
    private MerchantStatusOutbox statusOutbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Merchant reload(Merchant merchant) {
        return merchants.findById(merchant.getId()).orElseThrow();
    }

    private List<OutboxEvent> events(Merchant merchant) {
        return outbox.findByMerchantIdOrderByIdAsc(merchant.getId());
    }

    private static UpdateMerchantRequest active(boolean value) {
        UpdateMerchantRequest request = new UpdateMerchantRequest();
        request.setActive(value);
        return request;
    }

    // --- the change and its event

    @Test
    void deactivatingBumpsTheVersionAndWritesOneEvent() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantCode").value(merchant.getMerchantCode()))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.statusVersion").doesNotExist());

        Merchant after = reload(merchant);
        assertThat(after.isActive()).isFalse();
        assertThat(after.getStatusVersion()).isEqualTo(1);

        assertThat(events(merchant)).singleElement().satisfies(event -> {
            assertThat(event.getVersion()).isEqualTo(1);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttempts()).isZero();
            assertThat(event.getSentAt()).isNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStoredPayloadHasEveryField() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false}").andExpect(status().isOk());

        OutboxEvent event = events(merchant).getFirst();
        Map<String, Object> payload = JSON.readValue(event.getPayload(), Map.class);
        assertThat(payload).containsOnlyKeys(
                "schemaVersion", "eventId", "merchantId", "merchantCode", "active", "version", "occurredAt");
        assertThat(payload.get("schemaVersion")).isEqualTo(1);
        assertThat(UUID.fromString((String) payload.get("eventId"))).isEqualTo(event.getEventId());
        assertThat(((Number) payload.get("merchantId")).longValue()).isEqualTo(merchant.getId());
        assertThat(payload.get("merchantCode")).isEqualTo(merchant.getMerchantCode());
        assertThat(payload.get("active")).isEqualTo(false);
        assertThat(((Number) payload.get("version")).longValue()).isEqualTo(1);
        assertThat((String) payload.get("occurredAt")).endsWith("Z");
        assertThat(Instant.parse((String) payload.get("occurredAt"))).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void everyChangeIncreasesTheVersionByOne() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false}").andExpect(status().isOk());
        patchAsAdmin(merchant, "{\"active\":true}").andExpect(status().isOk());
        patchAsAdmin(merchant, "{\"active\":false}").andExpect(status().isOk());

        assertThat(reload(merchant).getStatusVersion()).isEqualTo(3);
        assertThat(events(merchant)).extracting(OutboxEvent::getVersion).containsExactly(1L, 2L, 3L);
    }

    // --- no event when nothing changes

    @Test
    void theSameValueIsA200NoOp() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(reload(merchant).getStatusVersion()).isZero();
        assertThat(events(merchant)).isEmpty();
    }

    @Test
    void commissionAndKycChangesPublishNothing() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"commissionRate\":9,\"kycVerified\":false}").andExpect(status().isOk());

        assertThat(reload(merchant).getStatusVersion()).isZero();
        assertThat(events(merchant)).isEmpty();
    }

    // --- PENDING

    @Test
    void activeOnAPendingMerchantIs409EvenWhenUnchanged() throws Exception {
        Merchant merchant = savePending("shop@example.com");

        for (String body : List.of("{\"active\":false}", "{\"active\":true}")) {
            patchAsAdmin(merchant, body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MERCHANT_NOT_LINKED"));
        }

        Merchant after = reload(merchant);
        assertThat(after.isActive()).isTrue();
        assertThat(after.getStatusVersion()).isZero();
        assertThat(events(merchant)).isEmpty();
    }

    @Test
    void the409AppliesNothingElseFromTheRequest() throws Exception {
        Merchant merchant = savePending("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false,\"commissionRate\":9,\"kycVerified\":false}")
                .andExpect(status().isConflict());

        Merchant after = reload(merchant);
        assertThat(after.getCommissionRate()).isEqualByComparingTo("5.00");
        assertThat(after.isKycVerified()).isTrue();
    }

    @Test
    void otherFieldsCanStillChangeWhilePending() throws Exception {
        Merchant merchant = savePending("shop@example.com");

        patchAsAdmin(merchant, "{\"commissionRate\":9}").andExpect(status().isOk());

        assertThat(reload(merchant).getCommissionRate()).isEqualByComparingTo("9.00");
    }

    // --- authorization order

    @Test
    void theAdminCheckAndTheLookupComeBeforeThePendingCheck() throws Exception {
        Merchant pending = savePending("shop@example.com");

        mockMvc.perform(bearer(MockMvcRequestBuilders.patch("/merchants/{code}", pending.getMerchantCode()),
                                TestTokens.buyer(5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(bearer(MockMvcRequestBuilders.patch("/merchants/{code}", "MCH-999999"), TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());
    }

    // --- one request, one transaction

    @Test
    void activeCombinesWithTheOtherFields() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false,\"commissionRate\":9,\"kycVerified\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.commissionRate").value(9.0))
                .andExpect(jsonPath("$.kycVerified").value(false));

        Merchant after = reload(merchant);
        assertThat(after.isActive()).isFalse();
        assertThat(after.getCommissionRate()).isEqualByComparingTo(new BigDecimal("9.00"));
        assertThat(after.isKycVerified()).isFalse();
        assertThat(events(merchant)).hasSize(1);
    }

    /** Both writes reach the database, then one rollback removes both: they share one transaction. */
    @Test
    void rollingBackRemovesTheChangeAndTheEventTogether() {
        Merchant merchant = saveLinked("shop@example.com");

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            updates.update(merchant.getMerchantCode(), active(false));
            merchants.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT active FROM merchant WHERE id = ?", Boolean.class,
                    merchant.getId())).isFalse();
            transaction.setRollbackOnly();
        });

        Merchant after = reload(merchant);
        assertThat(after.isActive()).isTrue();
        assertThat(after.getStatusVersion()).isZero();
        assertThat(events(merchant)).isEmpty();
    }

    /** The other direction: the outbox row cannot be written, so nothing of the PATCH survives. */
    @Test
    void aFailedEventWriteRollsBackTheWholePatch() {
        Merchant merchant = saveLinked("shop@example.com");
        // Takes the (merchant_id, version) that the PATCH is about to use.
        jdbc.update("INSERT INTO outbox_event (id, event_id, merchant_id, version, payload, status, created_at)"
                        + " VALUES (NEXTVAL('outbox_event_id_seq'), ?, ?, 1, '{}', 'SENT', CURRENT_TIMESTAMP)",
                UUID.randomUUID(), merchant.getId());

        assertThatThrownBy(() -> patchAsAdmin(merchant, "{\"active\":false,\"commissionRate\":9}"))
                .rootCause().hasMessageContainingAll("uq_outbox_event_merchant_version");

        Merchant after = reload(merchant);
        assertThat(after.isActive()).isTrue();
        assertThat(after.getStatusVersion()).isZero();
        assertThat(after.getCommissionRate()).isEqualByComparingTo("5.00");
        assertThat(events(merchant)).hasSize(1);
    }

    @Test
    void anOutboxRowCannotBeWrittenWithoutATransaction() {
        assertThatThrownBy(() -> statusOutbox.recordChange(1, "MCH-001", false, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
