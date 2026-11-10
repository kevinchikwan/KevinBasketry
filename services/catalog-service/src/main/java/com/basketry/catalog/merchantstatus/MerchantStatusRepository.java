package com.basketry.catalog.merchantstatus;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MerchantStatusRepository extends JpaRepository<MerchantStatus, Long> {

    /**
     * Applies one merchant.status.changed event in one atomic statement with no
     * read first (DESIGN.md Section 5): inserts the merchant's row if it has
     * none, replaces it if the event's version is newer, and otherwise changes
     * nothing, so duplicates and older events are ignored. Gaps are not
     * detected: an event carries the full state.
     *
     * <p>Standard SQL MERGE, so H2 and PostgreSQL run the same statement (H2
     * has no {@code ON CONFLICT DO UPDATE}). On PostgreSQL two instances
     * inserting the same new merchant at once can make one fail on the primary
     * key; that is a database error, which the listener retries, and the retry
     * then takes the MATCHED branch.
     *
     * @return 1 if the event was applied, 0 if it was not newer
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            MERGE INTO merchant_status t
            USING (SELECT CAST(:merchantId AS BIGINT) AS merchant_id, CAST(:active AS BOOLEAN) AS active,
                          CAST(:version AS BIGINT) AS version) s
            ON t.merchant_id = s.merchant_id
            WHEN MATCHED AND t.version < s.version THEN
                UPDATE SET active = s.active, version = s.version, updated_at = :now
            WHEN NOT MATCHED THEN
                INSERT (merchant_id, active, version, updated_at) VALUES (s.merchant_id, s.active, s.version, :now)
            """)
    int apply(long merchantId, boolean active, long version, Instant now);
}
