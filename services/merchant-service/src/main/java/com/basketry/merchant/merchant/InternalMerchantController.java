package com.basketry.merchant.merchant;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeSet;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /internal/merchants?ids=1,2,3, for order-service's checkout (DESIGN.md
 * Section 3, merchant-service): each merchant's live status and the commission
 * rate order-service snapshots onto the order. Internal key only, in its own
 * security chain.
 */
@RestController
public class InternalMerchantController {

    static final int MAX_IDS = 100;

    private final MerchantRepository merchants;

    public InternalMerchantController(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    /**
     * 1 to 100 positive ids, else 400; the limit counts the list as sent,
     * repeats included, and an empty element ({@code 1,,2}) is a 400. The
     * merchants that exist, in id order, a repeated id answered once; unknown
     * ids are left out. A PENDING merchant is answered like any other.
     */
    @GetMapping("/internal/merchants")
    @Transactional(readOnly = true)
    public Content list(@RequestParam List<Long> ids) {
        if (ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidRequestBodyException("ids must be 1 to " + MAX_IDS + " positive merchant ids.");
        }
        return new Content(merchants.findAllByIdInOrderById(new TreeSet<>(ids)).stream()
                .map(InternalMerchant::from)
                .toList());
    }

    public record Content(List<InternalMerchant> content) {
    }

    /** {@code commissionRate} keeps its scale: {@code 5.00}, never {@code 5.0}. */
    public record InternalMerchant(long id, boolean active, BigDecimal commissionRate) {

        static InternalMerchant from(Merchant merchant) {
            return new InternalMerchant(merchant.getId(), merchant.isActive(), merchant.getCommissionRate());
        }
    }
}
