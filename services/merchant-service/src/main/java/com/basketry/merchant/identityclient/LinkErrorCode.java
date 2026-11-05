package com.basketry.merchant.identityclient;

/**
 * Why a merchant-link attempt failed, as shown to Admin in
 * {@code accountLinkError.code}. The first four are identity-service's own 409
 * codes, passed through unchanged. In every case the merchant stays
 * {@code PENDING} and Admin can retry with POST /merchants/{code}/account-link.
 */
public enum LinkErrorCode {

    EMAIL_BELONGS_TO_ADMIN("This email is an admin login. Use a different email."),
    ACCOUNT_LINKED_TO_OTHER_MERCHANT("This login already runs another merchant. Use a different email."),
    BUYER_DEACTIVATED("This buyer account is deactivated. Reactivate it first (Accounts page), then retry."),
    MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT(
            "An earlier attempt already linked this merchant to a different login. Retry with the original email."),

    /** 400 from identity-service naming the failed password rules; {@code violations} lists them. */
    WEAK_PASSWORD("The initial password does not meet the password policy."),
    /** 400: no account exists for this email, so an initial password is needed, and none was sent. */
    INITIAL_PASSWORD_REQUIRED("No account exists for this email, so an initial password is required."),

    /** Timeout, connection failure or 5xx, after the one automatic retry. */
    IDENTITY_UNAVAILABLE("Merchant saved; login not linked yet. Retry."),
    /** 401: the internal API keys of the two services do not match. A configuration error, logged as such. */
    IDENTITY_AUTH_FAILED("Merchant saved; login not linked: the services' internal API keys do not match. "
            + "Fix the configuration, then retry."),
    /** Any other refusal, such as an unexpected 4xx or an unknown 409 code. Logged as an error. */
    LINK_REJECTED("Merchant saved; login not linked: identity-service rejected the request.");

    private final String adminMessage;

    LinkErrorCode(String adminMessage) {
        this.adminMessage = adminMessage;
    }

    public String adminMessage() {
        return adminMessage;
    }
}
