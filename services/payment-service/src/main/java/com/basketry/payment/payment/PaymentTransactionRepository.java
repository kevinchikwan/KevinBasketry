package com.basketry.payment.payment;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** The idempotency lookup of a charge. */
    Optional<PaymentTransaction> findByOrderIdAndMerchantId(long orderId, long merchantId);

    Optional<PaymentTransaction> findByTransactionRef(String transactionRef);

    /**
     * Only from APPROVED, in one statement, so two overlapping refunds move the
     * row once: the second updates nothing. Runs in the caller's transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query("update PaymentTransaction t set t.status = com.basketry.payment.payment.TransactionStatus.REFUNDED,"
            + " t.refundedAt = :refundedAt"
            + " where t.id = :id and t.status = com.basketry.payment.payment.TransactionStatus.APPROVED")
    int markRefunded(long id, Instant refundedAt);
}
