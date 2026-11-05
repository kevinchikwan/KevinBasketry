package com.basketry.merchant.merchant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByMerchantCode(String merchantCode);

    Optional<Merchant> findByLoginEmail(String loginEmail);

    /**
     * The id for the next merchant, taken before its row is built (see
     * {@link Merchant}). Read-write on purpose: query methods default to a
     * read-only transaction, and Postgres refuses nextval() in one.
     */
    @Transactional
    @Query(value = "SELECT nextval('merchant_id_seq')", nativeQuery = true)
    Long nextId();
}
