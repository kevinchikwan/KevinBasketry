package com.basketry.inventory.stock;

import com.basketry.inventory.counter.StockCounterGuard;
import com.basketry.inventory.counter.StockCounterGuard.Take;
import com.basketry.inventory.outbox.StockResultOutbox;
import com.basketry.inventory.stock.ReservationOutcome.ReservationFailed;
import com.basketry.inventory.stock.ReservationOutcome.Reserved;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reservations, releases, adjustments and stock-row creation (DESIGN.md
 * Section 2, inventory-service), with no transport types.
 *
 * <p><b>Only PostgreSQL decides a success.</b> The Redis counter is a fast
 * pre-check that can only lead to a rejection, and every rejection it suggests
 * is confirmed by a plain read of PostgreSQL first. So a counter that has
 * drifted, in either direction, costs speed and never a wrong answer.
 *
 * <p>Every reserve and release that returns writes exactly one outbox row with
 * its result, in the transaction that decided it. A repeated request is
 * answered from the stored row and publishes that result again; nothing is
 * taken or given back twice.
 *
 * <p>Redis is called only outside a database transaction, and no method here
 * runs inside a caller's transaction: after a lost race, a <em>new</em>
 * transaction must answer, because on PostgreSQL a failed statement aborts the
 * whole transaction.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockLevelRepository stockLevels;
    private final StockReservationRepository reservations;
    private final StockResultOutbox outbox;
    private final StockCounterGuard counter;
    private final TransactionTemplate transaction;

    public StockService(StockLevelRepository stockLevels, StockReservationRepository reservations,
                        StockResultOutbox outbox, StockCounterGuard counter,
                        PlatformTransactionManager transactionManager) {
        this.stockLevels = stockLevels;
        this.reservations = reservations;
        this.outbox = outbox;
        this.counter = counter;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * In this order: (1) a stored row answers, and Redis is not touched; (2) a
     * quantity over the limit fails; (3) the Redis pre-check, loading a missing
     * counter once, and a confirming read of PostgreSQL when it says
     * insufficient; (4) the PostgreSQL gate, in one transaction with the row and
     * its outbox row; (5) after the commit, a failed gate gives the counter back
     * what this call took from it, and a success that went past a counter saying
     * "insufficient" is subtracted from it. So every success is subtracted from
     * the counter exactly once, and only crashes, lost replies and outages make
     * it drift.
     */
    public ReservationOutcome reserve(ReserveCommand command) {
        Objects.requireNonNull(command, "command");
        ReservationOutcome repeated = transaction.execute(status -> answerReserveFromStoredRow(command));
        if (repeated != null) {
            return repeated;
        }
        if (command.quantity() > StockLimits.MAX_RESERVATION_QUANTITY) {
            return failWithoutTakingStock(command, ReasonCode.QUANTITY_OVER_LIMIT);
        }

        long productId = command.productId();
        Take take = counter.take(productId, command.quantity());
        if (take == Take.NOT_LOADED) {
            // No stock row: no counter is created, and the gate answers UNKNOWN_PRODUCT.
            Optional<Long> stored = stockLevels.findQuantity(productId);
            if (stored.isPresent()) {
                counter.loadIfAbsent(productId, stored.get());
                take = counter.take(productId, command.quantity());
            }
        }
        if (take == Take.INSUFFICIENT) {
            Optional<Long> available = stockLevels.findQuantity(productId);
            if (available.isPresent() && available.get() < command.quantity()) {
                return failWithoutTakingStock(command, ReasonCode.INSUFFICIENT_STOCK);
            }
            log.info("The stock counter of product {} is below PostgreSQL ({}); the gate decides order {}",
                    productId, available.map(String::valueOf).orElse("no stock row"), command.orderId());
        }
        boolean takenFromCounter = take == Take.TAKEN;
        boolean passedACounterThatSaidNo = take == Take.INSUFFICIENT;

        ReservationOutcome outcome;
        try {
            outcome = transaction.execute(status -> gate(command));
        } catch (DataIntegrityViolationException raceLost) {
            giveBackToCounter(takenFromCounter, command);
            ReservationOutcome winner = transaction.execute(status -> answerReserveFromStoredRow(command));
            if (winner == null) {
                throw raceLost; // not the (orderId, productId) key
            }
            return winner;
        } catch (RuntimeException e) {
            giveBackToCounter(takenFromCounter, command);
            throw e;
        }
        if (outcome instanceof ReservationFailed) {
            giveBackToCounter(takenFromCounter, command);
        } else if (passedACounterThatSaidNo) {
            // The counter said no, PostgreSQL said yes, and the gate took the stock.
            // Subtract it now (the counter may go below zero), so that every success
            // is subtracted exactly once. The counter often says no only because
            // another reservation took from it and has not committed yet; if that one
            // then loses the gate, it gives its take back, and without this the
            // counter would end up too high after ordinary concurrency.
            counter.addIfLoaded(productId, -command.quantity());
        }
        return outcome;
    }

    /**
     * Gives back the stored quantity of a RESERVED row, once. A release of a
     * RELEASED or FAILED row changes nothing. A release with no row stores a
     * FAILED tombstone (RELEASED_BEFORE_RESERVE), so a reserve arriving later
     * takes nothing. Every case publishes {@code released}.
     */
    public Released release(ReleaseCommand command) {
        Objects.requireNonNull(command, "command");
        long givenBack;
        try {
            givenBack = transaction.execute(status -> releaseInTransaction(command));
        } catch (DataIntegrityViolationException raceLost) {
            // Only the tombstone insert can hit the key: a reserve or release of the
            // same pair committed first. A new transaction now finds its row.
            givenBack = transaction.execute(status -> releaseInTransaction(command));
        }
        if (givenBack > 0) {
            counter.addIfLoaded(command.productId(), givenBack);
        }
        return new Released(command.orderId(), command.productId());
    }

    /**
     * Adds a delta, in one conditional statement, so it commutes with
     * reservations in flight. The counter gets the same delta, never a SET,
     * which would wipe the decrements of reservations in flight.
     */
    public StockSnapshot adjust(AdjustCommand command) {
        Objects.requireNonNull(command, "command");
        long productId = command.productId();
        long delta = command.delta();
        StockSnapshot after = transaction.execute(status -> {
            Instant now = Instant.now();
            int updated = delta > 0
                    ? stockLevels.increase(productId, delta, StockLimits.MAX_STOCK, now)
                    : stockLevels.decrease(productId, -delta, now);
            StockLevel row = stockLevels.findById(productId).orElseThrow(() -> new UnknownProductException(productId));
            if (updated == 0) {
                if (delta < 0) {
                    throw new StockWouldGoNegativeException(productId, row.getQuantity(), delta);
                }
                throw new StockLimitExceededException(productId, row.getQuantity(), delta);
            }
            return row.snapshot();
        });
        counter.addIfLoaded(productId, delta);
        log.info("Stock of product {} adjusted by {} to {}", productId, delta, after.quantity());
        return after;
    }

    /**
     * Creates a product's stock row at zero, so a new product shows as out of
     * stock. Repeating it for the same merchant changes nothing; another
     * merchant is refused. The counter is loaded when first needed.
     */
    public CreateOutcome create(CreateStockCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            return transaction.execute(status -> createInTransaction(command));
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent create committed first; a new transaction finds its row.
            return transaction.execute(status -> createInTransaction(command));
        }
    }

    private ReservationOutcome gate(ReserveCommand command) {
        Instant now = Instant.now();
        if (stockLevels.take(command.productId(), command.quantity(), now) == 1) {
            // A concurrent duplicate fails here, which rolls its take back.
            reservations.saveAndFlush(StockReservation.reserved(command.orderId(), command.productId(),
                    command.quantity(), now));
            outbox.recordReserved(command.orderId(), command.productId());
            log.info("Reserved {} of product {} for order {}", command.quantity(), command.productId(),
                    command.orderId());
            return new Reserved();
        }
        ReasonCode reason = stockLevels.existsById(command.productId())
                ? ReasonCode.INSUFFICIENT_STOCK
                : ReasonCode.UNKNOWN_PRODUCT;
        return storeFailure(command, reason, now);
    }

    /** A failure decided before the gate: nothing was taken from the counter or from PostgreSQL. */
    private ReservationOutcome failWithoutTakingStock(ReserveCommand command, ReasonCode reason) {
        try {
            return transaction.execute(status -> storeFailure(command, reason, Instant.now()));
        } catch (DataIntegrityViolationException raceLost) {
            ReservationOutcome winner = transaction.execute(status -> answerReserveFromStoredRow(command));
            if (winner == null) {
                throw raceLost;
            }
            return winner;
        }
    }

    /** Failures are stored too, so a redelivery after a restock still fails and takes nothing. */
    private ReservationOutcome storeFailure(ReserveCommand command, ReasonCode reason, Instant now) {
        reservations.saveAndFlush(StockReservation.failed(command.orderId(), command.productId(),
                command.quantity(), reason, now));
        outbox.recordReservationFailed(command.orderId(), command.productId(), reason);
        log.info("Reservation of {} of product {} for order {} failed: {}", command.quantity(),
                command.productId(), command.orderId(), reason);
        return new ReservationFailed(reason);
    }

    /**
     * Null if there is no stored row yet. A reserve is answered as a reserve: a
     * RELEASED row was reserved, so it answers {@code reserved} again (order-service
     * ignores it, as it ignores a repeated {@code charged} after a refund).
     */
    private ReservationOutcome answerReserveFromStoredRow(ReserveCommand command) {
        StockReservation stored = reservations.findByOrderIdAndProductId(command.orderId(), command.productId())
                .orElse(null);
        if (stored == null) {
            return null;
        }
        if (stored.getQuantity() != command.quantity()) {
            log.warn("Repeated reserve for order {}, product {} differs from the first: quantity {} (first {})."
                            + " Keeping the first result; nothing taken.", command.orderId(), command.productId(),
                    command.quantity(), stored.getQuantity());
        } else {
            log.info("Repeated reserve for order {}, product {}: publishing the stored result again",
                    command.orderId(), command.productId());
        }
        return switch (stored.getStatus()) {
            case RESERVED, RELEASED -> {
                outbox.recordReserved(command.orderId(), command.productId());
                yield new Reserved();
            }
            case FAILED -> {
                outbox.recordReservationFailed(command.orderId(), command.productId(), stored.getReasonCode());
                yield new ReservationFailed(stored.getReasonCode());
            }
        };
    }

    /** The quantity given back to PostgreSQL, and so owed to the counter; 0 if nothing was. */
    private long releaseInTransaction(ReleaseCommand command) {
        Instant now = Instant.now();
        long orderId = command.orderId();
        long productId = command.productId();
        StockReservation stored = reservations.findByOrderIdAndProductId(orderId, productId).orElse(null);
        if (stored == null) {
            reservations.saveAndFlush(StockReservation.failed(orderId, productId, command.quantity(),
                    ReasonCode.RELEASED_BEFORE_RESERVE, now));
            outbox.recordReleased(orderId, productId);
            log.warn("Release for order {}, product {} came before any reserve. Stored a tombstone, so a later"
                    + " reserve takes nothing.", orderId, productId);
            return 0;
        }
        if (stored.getQuantity() != command.quantity()) {
            log.warn("Release for order {}, product {} differs from the reservation: quantity {} (stored {})."
                    + " Releasing the stored quantity.", orderId, productId, command.quantity(), stored.getQuantity());
        }
        long givenBack = 0;
        switch (stored.getStatus()) {
            case RESERVED -> {
                // 0 rows: an overlapping release got there first and gave it back.
                if (reservations.markReleased(orderId, productId, now) == 1) {
                    if (stockLevels.giveBack(productId, stored.getQuantity(), now) != 1) {
                        // Rows are never deleted, so this is a bug or lost data.
                        throw new IllegalStateException("No stock row for product " + productId
                                + " although order " + orderId + " reserved it");
                    }
                    givenBack = stored.getQuantity();
                    log.info("Released {} of product {} for order {}", givenBack, productId, orderId);
                }
            }
            case RELEASED -> log.info("Repeated release for order {}, product {}: publishing released again",
                    orderId, productId);
            case FAILED -> log.info("Release for order {}, product {}, whose reservation failed ({}): nothing"
                    + " to give back", orderId, productId, stored.getReasonCode());
        }
        outbox.recordReleased(orderId, productId);
        return givenBack;
    }

    private CreateOutcome createInTransaction(CreateStockCommand command) {
        StockLevel existing = stockLevels.findById(command.productId()).orElse(null);
        if (existing != null) {
            if (existing.getMerchantId() != command.merchantId()) {
                throw new ProductOwnedByOtherMerchantException(command.productId());
            }
            return new CreateOutcome(existing.snapshot(), false);
        }
        StockLevel created = stockLevels.saveAndFlush(new StockLevel(command.productId(), command.merchantId(),
                Instant.now()));
        log.info("Created stock for product {} of merchant {}", command.productId(), command.merchantId());
        return new CreateOutcome(created.snapshot(), true);
    }

    private void giveBackToCounter(boolean takenFromCounter, ReserveCommand command) {
        if (takenFromCounter) {
            counter.addIfLoaded(command.productId(), command.quantity());
        }
    }
}
