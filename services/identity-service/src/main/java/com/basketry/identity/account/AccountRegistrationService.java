package com.basketry.identity.account;

import com.basketry.identity.Emails;
import com.basketry.identity.admin.AdminAccountRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRegistrationService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

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
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new PasswordTooLongException();
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
