package com.basketry.catalog.product;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.hibernate.jpa.SpecHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * How long a row-lock read waits for another transaction's lock before it
     * fails (Postgres waits forever by default). The caller turns that into
     * {@link ProductBusyException}. The same as merchant-service's.
     */
    String LOCK_TIMEOUT_MILLIS = "5000";

    /**
     * Reads and locks the row, for every read that leads to an update.
     * Hibernate writes all columns, so two overlapping PATCHes from plain reads
     * would let the later one put back a field the earlier one changed (DESIGN.md
     * Section 2, merchant-service, row lock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = SpecHints.HINT_SPEC_LOCK_TIMEOUT, value = LOCK_TIMEOUT_MILLIS))
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(long id);
}
