package com.basketry.identity.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** Expects an already-normalized (lowercase) email. */
    boolean existsByEmail(String email);

    /** Expects an already-normalized (lowercase) email. */
    Optional<Account> findByEmail(String email);

    /** At most one account per merchant (unique index, V3 migration). */
    Optional<Account> findByMerchantId(Long merchantId);

    boolean existsByMerchantId(Long merchantId);

    /**
     * Applies a merchant.status.changed event in one atomic statement: an event
     * that is not newer than the applied version changes nothing, so duplicates
     * and out-of-order delivery need no read first.
     *
     * @return 1 if applied, 0 if the event is stale or no account has this merchant
     */
    @Transactional
    @Modifying
    @Query("update Account a set a.merchantActive = :active, a.merchantStatusVersion = :version"
            + " where a.merchantId = :merchantId and a.merchantStatusVersion < :version")
    int applyMerchantStatus(long merchantId, boolean active, long version);
}
