package com.basketry.merchant.merchant;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hibernate.jpa.SpecHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Transactional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    /**
     * How long a row-lock read waits for another transaction's lock before it
     * fails (Postgres waits forever by default). The caller turns that into a
     * 503 {@link MerchantBusyException}.
     */
    String LOCK_TIMEOUT_MILLIS = "5000";

    Optional<Merchant> findByMerchantCode(String merchantCode);

    Optional<Merchant> findByLoginEmail(String loginEmail);

    /**
     * For GET /internal/merchants: the merchants that exist among {@code ids},
     * in id order. A plain read with no row lock, so a PATCH holding a
     * merchant's lock never makes a checkout wait; the answer is the last
     * committed state.
     */
    @Query("select m from Merchant m where m.id in :ids order by m.id")
    List<Merchant> findAllByIdInOrderById(Collection<Long> ids);

    /**
     * Reads and locks the row, for every read that leads to an update.
     * Hibernate writes all columns, so two overlapping updates from plain reads
     * would let the later one put back the value the earlier one changed, such
     * as active, with no event. Only for short transactions: never hold this
     * during a call to identity-service.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = SpecHints.HINT_SPEC_LOCK_TIMEOUT, value = LOCK_TIMEOUT_MILLIS))
    @Query("select m from Merchant m where m.merchantCode = :merchantCode")
    Optional<Merchant> findByMerchantCodeForUpdate(String merchantCode);

    /** See {@link #findByMerchantCodeForUpdate}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = SpecHints.HINT_SPEC_LOCK_TIMEOUT, value = LOCK_TIMEOUT_MILLIS))
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
