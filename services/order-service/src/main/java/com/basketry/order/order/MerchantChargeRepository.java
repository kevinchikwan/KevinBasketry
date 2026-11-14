package com.basketry.order.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantChargeRepository extends JpaRepository<MerchantCharge, MerchantCharge.Key> {

    List<MerchantCharge> findByOrderIdOrderByMerchantIdAsc(long orderId);

    /** For a merchant's page of summaries: its own charge legs of these orders. */
    List<MerchantCharge> findByOrderIdInAndMerchantId(Collection<Long> orderIds, long merchantId);
}
