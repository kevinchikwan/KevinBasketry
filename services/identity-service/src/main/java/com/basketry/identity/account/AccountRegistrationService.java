package com.basketry.identity.account;

import com.basketry.identity.Emails;
import com.basketry.identity.PasswordPolicy;
import com.basketry.identity.admin.AdminAccountRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRegistrationService {

    private final AccountRepository accounts;
    private final AdminAccountRepository adminAccounts;
    private final PasswordEncoder passwordEncoder;

    public AccountRegistrationService(AccountRepository accounts,
                                      AdminAccountRepository adminAccounts,
                                      PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.adminAccounts = adminAccounts;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a buyer account. Only an email and password come in, so a
     * merchant link can never be set from here.
     */
    @Transactional
    public Account registerBuyer(String rawEmail, String password) {
        // The authoritative policy check; the front-end's checklist is only a convenience.
        List<String> violations = PasswordPolicy.violations(password);
        if (!violations.isEmpty()) {
            throw new WeakPasswordException(violations);
        }

        String email = Emails.normalize(rawEmail);
        // Email is unique across both tables (DESIGN.md Section 2).
        if (accounts.existsByEmail(email) || adminAccounts.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(null);
        }

        try {
            return accounts.saveAndFlush(new Account(email, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations passed the check; the unique constraint caught the second.
            throw new EmailAlreadyRegisteredException(e);
        }
    }
}
