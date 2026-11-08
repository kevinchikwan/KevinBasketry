package com.basketry.merchant.merchant;

import com.basketry.merchant.outbox.MerchantStatusOutbox;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PATCH /merchants/{code} (FR-A3, FR-A4). One transaction: the fields, the
 * status version and the outbox row all commit or none does. A 409 applies
 * nothing, not even the other fields of the request.
 */
@Service
public class MerchantUpdateService {

    private final MerchantRepository merchants;
    private final MerchantStatusOutbox statusOutbox;

    public MerchantUpdateService(MerchantRepository merchants, MerchantStatusOutbox statusOutbox) {
        this.merchants = merchants;
        this.statusOutbox = statusOutbox;
    }

    @Transactional
    public MerchantResponse update(String code, UpdateMerchantRequest request) {
        Merchant merchant;
        try {
            merchant = merchants.findByMerchantCodeForUpdate(code).orElseThrow(MerchantNotFoundException::new);
        } catch (PessimisticLockingFailureException lockTimedOut) {
            throw new MerchantBusyException(lockTimedOut);
        }
        if (request.hasActive() && merchant.getAccountLinkStatus() == AccountLinkStatus.PENDING) {
            throw new MerchantNotLinkedException();
        }
        request.applyFieldsTo(merchant);
        if (request.hasActive() && merchant.changeActive(request.getActive())) {
            statusOutbox.recordChange(merchant.getId(), merchant.getMerchantCode(), merchant.isActive(),
                    merchant.getStatusVersion());
        }
        return MerchantResponse.from(merchant);
    }
}
