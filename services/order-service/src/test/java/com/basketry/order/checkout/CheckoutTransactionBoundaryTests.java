package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.basketry.order.downstream.CatalogClient;
import com.basketry.order.downstream.IdentityClient;
import com.basketry.order.downstream.MerchantClient;
import com.basketry.order.support.FakeServices;
import com.basketry.order.support.OrderServiceTestBase;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * No transaction and no database connection is held while checkout waits for
 * identity-service, catalog-service and merchant-service (DESIGN.md Section 2,
 * order-service, checkout in order): a slow service must never pin a
 * connection of the small pool. Checked twice for each call: on checkout's own
 * thread just before the call (a transaction is a thread-local, so only that
 * thread can see it), and on the fake service's thread while it answers.
 */
class CheckoutTransactionBoundaryTests extends OrderServiceTestBase {

    @MockitoSpyBean
    private IdentityClient identity;

    @MockitoSpyBean
    private CatalogClient catalog;

    @MockitoSpyBean
    private MerchantClient merchantClient;

    @Autowired
    private DataSource dataSource;

    private final Map<String, String> seen = new ConcurrentHashMap<>();

    private int activeConnections() {
        return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
    }

    private Answer<Object> recording(String call) {
        return invocation -> {
            seen.put(call + " on checkout's thread", "transaction="
                    + TransactionSynchronizationManager.isActualTransactionActive()
                    + ", activeConnections=" + activeConnections());
            return invocation.callRealMethod();
        };
    }

    /** Mutation: {@code @Transactional} on CheckoutService.checkout(). */
    @Test
    void noTransactionAndNoConnectionDuringTheThreeCalls() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
        doAnswer(recording("identity")).when(identity).account(anyLong(), anyString());
        doAnswer(recording("catalog")).when(catalog).products(any());
        doAnswer(recording("merchant")).when(merchantClient).merchants(any());
        services.whileAnswering(path -> seen.put(path + " while answering", "activeConnections=" + activeConnections()));

        assertThat(checkout(command("key-0001", "2.50", 10, 1)).created()).isTrue();

        String idle = "transaction=false, activeConnections=0";
        assertThat(seen).containsAllEntriesOf(Map.of(
                "identity on checkout's thread", idle,
                "catalog on checkout's thread", idle,
                "merchant on checkout's thread", idle,
                FakeServices.ACCOUNTS + " while answering", "activeConnections=0",
                FakeServices.PRODUCTS + " while answering", "activeConnections=0",
                FakeServices.MERCHANTS + " while answering", "activeConnections=0"));
        assertThat(rowCounts()).isEqualTo(List.of(1, 1, 1, 2));
    }
}
