package com.basketry.merchant.merchant;

import com.basketry.merchant.Emails;
import com.basketry.merchant.identityclient.IdentityClient;
import com.basketry.merchant.identityclient.InternalApiKey;
import com.basketry.merchant.identityclient.LinkResult;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Onboarding (FR-A1, DESIGN.md Section 3, merchant-service):
 *
 * <ol>
 *   <li>Save the merchant as PENDING and commit. It needs an id before
 *       identity-service can link an account to it.</li>
 *   <li>Call merchant-link, outside any transaction, so no database
 *       transaction stays open during the remote call.</li>
 *   <li>On any 2xx, mark the merchant LINKED. Otherwise it stays PENDING and
 *       Admin retries with {@link #retryLink}, which reuses the same
 *       merchantId: after a timeout that actually succeeded, the retry gets
 *       200 ALREADY_LINKED instead of creating anything twice.</li>
 * </ol>
 *
 * The initial password is only passed on to identity-service, never stored.
 */
@Service
public class MerchantOnboardingService {

    private final MerchantRepository merchants;
    private final IdentityClient identity;
    private final InternalApiKey apiKey;
    private final TransactionTemplate transaction;

    public MerchantOnboardingService(MerchantRepository merchants,
                                     IdentityClient identity,
                                     InternalApiKey apiKey,
                                     PlatformTransactionManager transactionManager) {
        this.merchants = merchants;
        this.identity = identity;
        this.apiKey = apiKey;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public OnboardingResponse onboard(CreateMerchantRequest request) {
        requireApiKey();
        String email = Emails.normalize(request.loginEmail());
        Merchant merchant = create(request, email);
        return link(merchant.getId(), email, request.initialPassword());
    }

    /**
     * Retries the link for a PENDING merchant. {@code email} defaults to the
     * stored loginEmail. The stored loginEmail changes only if this attempt
     * succeeds.
     */
    public OnboardingResponse retryLink(String merchantCode, AccountLinkRequest request) {
        Merchant merchant = merchants.findByMerchantCode(merchantCode).orElseThrow(MerchantNotFoundException::new);
        if (merchant.getAccountLinkStatus() == AccountLinkStatus.LINKED) {
            throw new MerchantAlreadyLinkedException();
        }
        requireApiKey();
        String email = request.email() == null ? merchant.getLoginEmail() : Emails.normalize(request.email());
        Optional<Merchant> holder = merchants.findByLoginEmail(email);
        if (holder.isPresent() && !holder.get().getId().equals(merchant.getId())) {
            throw new LoginEmailInUseException(holder.get().getMerchantCode());
        }
        return link(merchant.getId(), email, request.initialPassword());
    }

    /** Refuses before anything is saved, so no merchant is left PENDING with no way to link it. */
    private void requireApiKey() {
        if (!apiKey.isConfigured()) {
            throw new OnboardingUnavailableException();
        }
    }

    private Merchant create(CreateMerchantRequest request, String email) {
        try {
            return transaction.execute(status -> {
                Optional<Merchant> holder = merchants.findByLoginEmail(email);
                if (holder.isPresent()) {
                    throw new LoginEmailInUseException(holder.get().getMerchantCode());
                }
                Merchant merchant = new Merchant(merchants.nextId(), request.name(), request.category(),
                        request.kycVerified(), request.commissionRate(), email, Instant.now());
                return merchants.saveAndFlush(merchant);
            });
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent onboarding with the same email committed between our check and our insert.
            throw emailInUse(email, raceLost);
        }
    }

    private OnboardingResponse link(long merchantId, String email, String initialPassword) {
        // The remote call runs outside any transaction, so the row lock below is only
        // ever held for this short update, never while waiting for identity-service.
        LinkResult result = identity.link(email, merchantId, initialPassword);
        try {
            Merchant merchant = transaction.execute(status -> {
                Merchant current = merchants.findByIdForUpdate(merchantId).orElseThrow();
                if (result instanceof LinkResult.Linked) {
                    current.markLinked(email);
                    merchants.flush();
                }
                return current;
            });
            return OnboardingResponse.of(merchant, result);
        } catch (DataIntegrityViolationException raceLost) {
            // A retry with a new email that another merchant took in the meantime.
            throw emailInUse(email, raceLost);
        } catch (PessimisticLockingFailureException lockTimedOut) {
            // The merchant stays as it was (PENDING). identity-service may have linked
            // already; a retry of the link then gets ALREADY_LINKED and completes it.
            throw new MerchantBusyException(Merchant.codeFor(merchantId), lockTimedOut);
        }
    }

    private RuntimeException emailInUse(String email, DataIntegrityViolationException cause) {
        return merchants.findByLoginEmail(email)
                .<RuntimeException>map(holder -> new LoginEmailInUseException(holder.getMerchantCode()))
                .orElse(cause);
    }
}
