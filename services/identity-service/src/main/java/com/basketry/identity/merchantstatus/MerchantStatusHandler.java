package com.basketry.identity.merchantstatus;

import com.basketry.identity.account.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies one {@code merchant.status.changed} value (DESIGN.md Section 5).
 *
 * <p>The value is read with the service's own mapper, so the strict rules for
 * request bodies apply here too: {@code "active":"false"} or {@code 1} is
 * refused, never converted.
 *
 * <p>The update itself decides whether the event is newer, so duplicates and
 * out-of-order delivery change nothing and need no read first. Only an event
 * that changed no row is looked at again, to tell the log which case it was.
 */
@Service
public class MerchantStatusHandler {

    private static final Logger log = LoggerFactory.getLogger(MerchantStatusHandler.class);
    private static final int SCHEMA_VERSION = 1;

    private final AccountRepository accounts;
    private final JsonMapper json;

    public MerchantStatusHandler(AccountRepository accounts, JsonMapper json) {
        this.accounts = accounts;
        this.json = json;
    }

    /** @throws InvalidMerchantStatusEventException if the value can never be applied */
    public void handle(String value) {
        MerchantStatusEvent event = parse(value);
        if (accounts.applyMerchantStatus(event.merchantId(), event.active(), event.version()) == 1) {
            log.info("Merchant {} is now active={} (version {}, event {})",
                    event.merchantId(), event.active(), event.version(), event.eventId());
        } else if (accounts.existsByMerchantId(event.merchantId())) {
            log.debug("Ignored status event of merchant {}: version {} is not newer",
                    event.merchantId(), event.version());
        } else {
            // Only after data loss: while a merchant has no link, merchant-service refuses to change active.
            log.warn("Skipped status event of merchant {} (version {}, event {}): no account is linked to it",
                    event.merchantId(), event.version(), event.eventId());
        }
    }

    private MerchantStatusEvent parse(String value) {
        if (value == null) {
            throw new InvalidMerchantStatusEventException("The record has no value");
        }
        MerchantStatusEvent event;
        try {
            JsonNode tree = json.readTree(value);
            if (!tree.isObject()) {
                throw new InvalidMerchantStatusEventException("The value is not a JSON object");
            }
            // First, because another schema version may not have the fields below.
            JsonNode schemaVersion = tree.path("schemaVersion");
            if (!schemaVersion.isInt() || schemaVersion.intValue() != SCHEMA_VERSION) {
                throw new InvalidMerchantStatusEventException("Unknown schemaVersion: " + schemaVersion);
            }
            event = json.treeToValue(tree, MerchantStatusEvent.class);
        } catch (JacksonException unreadable) {
            throw new InvalidMerchantStatusEventException("Unreadable value: " + unreadable.getOriginalMessage(),
                    unreadable);
        }
        if (event.merchantId() == null || event.merchantId() <= 0) {
            throw new InvalidMerchantStatusEventException("merchantId is missing or not positive");
        }
        if (event.active() == null) {
            throw new InvalidMerchantStatusEventException("active is missing");
        }
        if (event.version() == null || event.version() <= 0) {
            throw new InvalidMerchantStatusEventException("version is missing or not positive");
        }
        return event;
    }
}
