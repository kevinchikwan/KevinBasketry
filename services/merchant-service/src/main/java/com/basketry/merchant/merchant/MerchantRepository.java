package com.basketry.merchant.merchant;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByMerchantCode(String merchantCode);

    Optional<Merchant> findByLoginEmail(String loginEmail);

    /**
     * Reads and locks the row, for every read that leads to an update.
     * Hibernate writes all columns, so two overlapping updates from plain reads
     * would let the later one put back the value the earlier one changed, such
     * as active, with no event. Only for short transactions: never hold this
     * during a call to identity-service.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Merchant m where m.merchantCode = :merchantCode")
    Optional<Merchant> findByMerchantCodeForUpdate(String merchantCode);

    /** See {@link #findByMerchantCodeForUpdate}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Merchant m where m.id = :id")
    Optional<Merchant> findByIdForUpdate(long id);

    /**
     * The id for the next merchant, taken before its row is built (see
     * {@link Merchant}). Read-write on purpose: query methods default to a
     * read-only transaction, and Postgres refuses nextval() in one.
     */
    @Transactional
    @Query(value = "SELECT nextval('merchant_id_seq')", nativeQuery = true)
    Long nextId();
}
