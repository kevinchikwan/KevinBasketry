package com.basketry.order.downstream;

import java.util.OptionalLong;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * identity-service's GET /accounts/{id}, the live buyer check at checkout
 * (FR-A6; DESIGN.md Section 2, order-service, checkout step 5). The buyer's
 * own token is forwarded: the endpoint allows "self". The answer's email is
 * read by nobody, never stored and never logged.
 */
@Component
public class IdentityClient {

    static final String SERVICE = "identity-service";
    private static final String CODE = DownstreamUnavailableException.IDENTITY;

    private final RestClient restClient;

    public IdentityClient(RestClient identityRestClient) {
        this.restClient = identityRestClient;
    }

    /**
     * @throws AccountNotFoundException       identity-service answered 404 (data loss)
     * @throws DownstreamUnavailableException anything else but a readable 200
     */
    public BuyerAccount account(long accountId, String bearerToken) {
        HttpAnswer answer = HttpAnswer.get(restClient.get()
                .uri("/accounts/{id}", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken), CODE, SERVICE);
        if (answer.status() == 404) {
            throw new AccountNotFoundException();
        }
        if (answer.status() != 200) {
            throw answer.unexpected(CODE, SERVICE,
                    "it refused a token order-service accepted; its signing key may have changed");
        }
        JsonNode account = answer.jsonObject(CODE, SERVICE);
        boolean buyerActive = HttpAnswer.required(account, "buyerActive", JsonNode::isBoolean,
                JsonNode::booleanValue, CODE, SERVICE);
        JsonNode merchantId = account.get("merchantId");
        if (merchantId == null || merchantId.isNull()) {
            return new BuyerAccount(buyerActive, OptionalLong.empty());
        }
        if (!merchantId.isIntegralNumber() || !merchantId.canConvertToLong()) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "field merchantId has the wrong type");
        }
        return new BuyerAccount(buyerActive, OptionalLong.of(merchantId.longValue()));
    }

    /**
     * @param buyerActive whether the account may check out
     * @param merchantId  the merchant the account is linked to, live: for the OWN_PRODUCT rule
     */
    public record BuyerAccount(boolean buyerActive, OptionalLong merchantId) {
    }

    /** identity-service does not know the token's account: only data loss causes it. */
    public static class AccountNotFoundException extends RuntimeException {

        AccountNotFoundException() {
            super("identity-service does not know the buyer's account");
        }
    }
}
