package com.basketry.identity.merchantlink;

import com.basketry.identity.Emails;
import com.basketry.identity.PasswordPolicy;
import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.account.WeakPasswordException;
import com.basketry.identity.admin.AdminAccountRepository;
import com.basketry.identity.merchantlink.MerchantLinkConflictException.Code;
import com.basketry.identity.merchantlink.MerchantLinkResponse.Outcome;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The merchant-link upsert by email (DESIGN.md Section 3). Checks run in this
 * order, and the first that applies decides the answer:
 * <ol>
 *   <li>email belongs to an AdminAccount: 409</li>
 *   <li>merchantId already linked to a different account: 409</li>
 *   <li>no account with this email: create it, linked (201)</li>
 *   <li>account linked to a different merchant: 409</li>
 *   <li>account already linked to this merchant: no change (200). Comes
 *       before the deactivated check, so a repeat of an earlier successful
 *       call is always 200.</li>
 *   <li>buyer deactivated: 409</li>
 *   <li>link the existing account, keeping its password (200)</li>
 * </ol>
 */
@Service
public class MerchantLinkService {

    private final AccountRepository accounts;
    private final AdminAccountRepository adminAccounts;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    public MerchantLinkService(AccountRepository accounts,
                               AdminAccountRepository adminAccounts,
                               PasswordEncoder passwordEncoder,
                               PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.adminAccounts = adminAccounts;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Each attempt runs in its own transaction. If a concurrent write (a
     * registration of the same email, or a link of the same merchantId) lands
     * between the checks and the write, a unique constraint rejects ours; the
     * call is then retried once, and the retry sees the other write and
     * answers accordingly.
     */
    public MerchantLinkResponse link(String rawEmail, long merchantId, String initialPassword) {
        String email = Emails.normalize(rawEmail);
        try {
            return transaction.execute(status -> attempt(email, merchantId, initialPassword));
        } catch (DataIntegrityViolationException raceLost) {
            return transaction.execute(status -> attempt(email, merchantId, initialPassword));
        }
    }

    private MerchantLinkResponse attempt(String email, long merchantId, String initialPassword) {
        if (adminAccounts.existsByEmail(email)) {
            throw new MerchantLinkConflictException(Code.EMAIL_BELONGS_TO_ADMIN);
        }

        // A repeat call by the account that already holds this merchant is not a conflict.
        Optional<Account> holder = accounts.findByMerchantId(merchantId);
        if (holder.isPresent() && !holder.get().getEmail().equals(email)) {
            throw new MerchantLinkConflictException(Code.MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT);
        }

        Optional<Account> existing = accounts.findByEmail(email);
        if (existing.isEmpty()) {
            return create(email, merchantId, initialPassword);
        }

        Account account = existing.get();
        if (account.getMerchantId() != null && account.getMerchantId() != merchantId) {
            throw new MerchantLinkConflictException(Code.ACCOUNT_LINKED_TO_OTHER_MERCHANT);
        }
        if (account.getMerchantId() != null) {
            // Same merchant: change nothing, not even merchantActive (only the event changes it now).
            return MerchantLinkResponse.of(account, Outcome.ALREADY_LINKED);
        }
        if (!account.isBuyerActive()) {
            throw new MerchantLinkConflictException(Code.BUYER_DEACTIVATED);
        }

        // The existing password is kept; initialPassword is ignored entirely.
        account.linkMerchant(merchantId);
        accounts.flush(); // surfaces a merchant_id unique violation inside the retry window
        return MerchantLinkResponse.of(account, Outcome.LINKED);
    }

    private MerchantLinkResponse create(String email, long merchantId, String initialPassword) {
        if (initialPassword == null) {
            throw new InitialPasswordRequiredException();
        }
        // The policy applies only here, where the application sets a password (REQUIREMENTS Section 4).
        List<String> violations = PasswordPolicy.violations(initialPassword);
        if (!violations.isEmpty()) {
            throw new WeakPasswordException(violations);
        }

        Account account = new Account(email, passwordEncoder.encode(initialPassword));
        account.linkMerchant(merchantId);
        return MerchantLinkResponse.of(accounts.saveAndFlush(account), Outcome.CREATED);
    }
}
