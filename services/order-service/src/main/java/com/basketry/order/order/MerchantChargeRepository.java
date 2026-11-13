package com.basketry.order.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantChargeRepository extends JpaRepository<MerchantCharge, MerchantCharge.Key> {

    List<MerchantCharge> findByOrderIdOrderByMerchantIdAsc(long orderId);
}
