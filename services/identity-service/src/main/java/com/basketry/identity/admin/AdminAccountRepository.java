package com.basketry.identity.admin;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Read-only: admin rows are created only by Flyway migrations. */
public interface AdminAccountRepository extends Repository<AdminAccount, Long> {

    /** Expects an already-normalized (lowercase) email. */
    boolean existsByEmail(String email);

    /** Expects an already-normalized (lowercase) email. */
    Optional<AdminAccount> findByEmail(String email);
}
