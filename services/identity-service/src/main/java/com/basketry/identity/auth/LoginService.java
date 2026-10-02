package com.basketry.identity.auth;

import com.basketry.identity.Emails;
import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.admin.AdminAccount;
import com.basketry.identity.admin.AdminAccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One login for all three front-ends (DESIGN.md Section 3): checks Account
 * first, then falls back to AdminAccount. The capabilities in the token, not
 * the URL the user logged in from, decide what the holder can do.
 */
@Service
public class LoginService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final AccountRepository accounts;
    private final AdminAccountRepository adminAccounts;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;

    /**
     * Compared against when the email is unknown, so an unknown email costs one
     * BCrypt check like a known one, and response time does not reveal which
     * emails are registered.
     */
    private final String dummyHash;

    public LoginService(AccountRepository accounts,
                        AdminAccountRepository adminAccounts,
                        PasswordEncoder passwordEncoder,
                        TokenIssuer tokenIssuer) {
        this.accounts = accounts;
        this.adminAccounts = adminAccounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String rawEmail, String password) {
        // No stored password can be this long (registration rejects it), and the
        // BCrypt encoder refuses such input, so treat it as a wrong password.
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new InvalidCredentialsException();
        }

        String email = Emails.normalize(rawEmail);

        Optional<Account> account = accounts.findByEmail(email);
        if (account.isPresent()) {
            return loginAccount(account.get(), password);
        }
        Optional<AdminAccount> admin = adminAccounts.findByEmail(email);
        if (admin.isPresent()) {
            return loginAdmin(admin.get(), password);
        }

        passwordEncoder.matches(password, dummyHash);
        throw new InvalidCredentialsException();
    }

    private LoginResponse loginAccount(Account account, String password) {
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        // buyerActive gates login everywhere, whatever merchantActive says.
        if (!account.isBuyerActive()) {
            throw new AccountDeactivatedException();
        }
        // MERCHANT is added only if the link is active at login time.
        boolean merchant = account.getMerchantId() != null && account.isMerchantActive();
        List<Capability> capabilities = merchant
                ? List.of(Capability.BUYER, Capability.MERCHANT)
                : List.of(Capability.BUYER);
        return response(tokenIssuer.issue(account.getId(), capabilities, account.getMerchantId()));
    }

    private LoginResponse loginAdmin(AdminAccount admin, String password) {
        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return response(tokenIssuer.issue(admin.getId(), List.of(Capability.ADMIN), null));
    }

    private static LoginResponse response(String token) {
        return new LoginResponse(token, "Bearer", TokenIssuer.TOKEN_LIFETIME.toSeconds());
    }
}
