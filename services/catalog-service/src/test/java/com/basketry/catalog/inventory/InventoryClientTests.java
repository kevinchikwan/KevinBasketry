package com.basketry.catalog.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.catalog.inventory.StockRowAnswer.Kind;
import com.basketry.catalog.support.CatalogServiceTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Row 10 of the Day 12 review: how each answer of POST /internal/stock is read, over real HTTP. */
@ExtendWith(OutputCaptureExtension.class)
class InventoryClientTests extends CatalogServiceTestBase {

    @Test
    void theRequestCarriesTheKeyAndExactlyTheTwoIds() {
        inventoryClient.createStockRow(42, MERCHANT);

        FakeCall call = FakeCall.of(inventory.calls("POST").getFirst(), json.readTree(inventory.calls("POST")
                .getFirst().body()));
        assertThat(call.uri()).isEqualTo("/internal/stock");
        assertThat(call.apiKey()).isEqualTo(API_KEY);
        assertThat(call.fields()).containsExactlyInAnyOrder("productId", "merchantId");
        assertThat(call.body().get("productId").asLong()).isEqualTo(42);
        assertThat(call.body().get("merchantId").asLong()).isEqualTo(MERCHANT);
    }

    /** 201 and a repeat's 200 are both done. Mutation: treat 200 ALREADY_EXISTS as a failure. */
    @Test
    void createdAndAlreadyExistsAreBothDone() {
        assertThat(inventoryClient.createStockRow(42, MERCHANT).kind()).isEqualTo(Kind.DONE);
        assertThat(inventoryClient.createStockRow(42, MERCHANT)).isEqualTo(new StockRowAnswer(Kind.DONE,
                "ALREADY_EXISTS"));
    }

    /** Mutation: read the 409 as retryable (the relay would call forever for a row it can never create). */
    @Test
    void anotherMerchantsRowIsAConflict() {
        inventory.givenStock(42, OTHER_MERCHANT, 0);

        assertThat(inventoryClient.createStockRow(42, MERCHANT).kind()).isEqualTo(Kind.CONFLICT);
    }

    /** Mutation: retry a 400 forever (a request that can never succeed would block the relay). */
    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void anotherClientErrorIsRejected(int status) {
        inventory.answerCreatesWith(status);

        assertThat(inventoryClient.createStockRow(42, MERCHANT).kind()).isEqualTo(Kind.REJECTED);
    }

    /** Mutation: treat 401 as REJECTED (fixing the key would never create the waiting rows). */
    @Test
    void aRefusedKeyIsRetriedAndLoggedAsAnError(CapturedOutput output) {
        RestClient restClient = RestClient.builder().baseUrl(inventory.baseUrl()).build();
        InventoryClient keyless = new InventoryClient(restClient, new com.basketry.catalog.config.InternalApiKey(""));

        assertThat(keyless.createStockRow(42, MERCHANT).kind()).isEqualTo(Kind.RETRY);
        assertThat(output).contains("ERROR").contains("inventory-service refused the internal API key (401)")
                .doesNotContain(API_KEY);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void aServerErrorIsRetried(int status) {
        inventory.answerCreatesWith(status);

        assertThat(inventoryClient.createStockRow(42, MERCHANT).kind()).isEqualTo(Kind.RETRY);
    }

    @Test
    void noConnectionIsRetried() {
        RestClient nowhere = RestClient.builder().baseUrl("http://127.0.0.1:1").build();

        assertThat(new InventoryClient(nowhere, new com.basketry.catalog.config.InternalApiKey(API_KEY))
                .createStockRow(42, MERCHANT).kind()).isEqualTo(Kind.RETRY);
    }

    @Test
    void aStockReadAnswersOnlyTheProductsWithARow() {
        inventory.givenStock(1, MERCHANT, 5);
        inventory.givenStock(3, MERCHANT, 0);

        assertThat(inventoryClient.stockOf(List.of(1L, 2L, 3L))).containsExactlyInAnyOrderEntriesOf(
                Map.of(1L, 5L, 3L, 0L));
    }

    @Test
    void aStockReadThatIsNot200IsUnavailable() {
        inventory.answerReadsWith(401);

        assertThatThrownBy(() -> inventoryClient.stockOf(List.of(1L)))
                .isInstanceOf(InventoryUnavailableException.class).hasMessageContaining("401");
    }

    private record FakeCall(String uri, String apiKey, JsonNode body, List<String> fields) {

        static FakeCall of(com.basketry.catalog.support.FakeInventoryServer.Call call, JsonNode body) {
            return new FakeCall(call.uri(), call.apiKey(), body, body.propertyNames().stream().toList());
        }
    }
}
