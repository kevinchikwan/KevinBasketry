package com.basketry.identity.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** Expects an already-normalized (lowercase) email. */
    boolean existsByEmail(String email);

    /** Expects an already-normalized (lowercase) email. */
    Optional<Account> findByEmail(String email);
}
