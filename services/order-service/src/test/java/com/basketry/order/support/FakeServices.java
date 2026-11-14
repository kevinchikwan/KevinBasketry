package com.basketry.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * One real HTTP server standing in for the three services checkout calls, so
 * order-service's clients run their real HTTP path (timeouts, headers, status
 * codes, bodies):
 *
 * <ul>
 *   <li>identity-service's GET /accounts/{id}: needs the account's own bearer
 *       token (401 otherwise, as identity-service answers a token it cannot
 *       verify), 404 for an unknown account;</li>
 *   <li>catalog-service's GET /internal/products?ids= and merchant-service's
 *       GET /internal/merchants?ids=: need the internal key (401 otherwise),
 *       answer the rows they have in id order, unknown ids left out.</li>
 * </ul>
 *
 * <p>A test can make one path answer a fixed status or body, or answer late.
 */
public final class FakeServices {

    public static final String ACCOUNTS = "/accounts";
    public static final String PRODUCTS = "/internal/products";
    public static final String MERCHANTS = "/internal/merchants";

    private static FakeServices shared;

    private final HttpServer server;
    private final String apiKey;
    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();
    private final Map<Long, Product> products = new ConcurrentHashMap<>();
    private final Map<Long, Merchant> merchants = new ConcurrentHashMap<>();
    private final Map<String, Fixed> fixed = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    /** Runs on the server's thread while a request is being answered, with the path; null for none. */
    private volatile java.util.function.Consumer<String> whileAnswering;

    private FakeServices(String apiKey) {
        this.apiKey = apiKey;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-services");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext(ACCOUNTS, exchange -> handle(exchange, ACCOUNTS));
        server.createContext(PRODUCTS, exchange -> handle(exchange, PRODUCTS));
        server.createContext(MERCHANTS, exchange -> handle(exchange, MERCHANTS));
        // identity-service's public keys, for verifying bearer tokens: always TestTokens.KEY.
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, 200,
                new com.nimbusds.jose.jwk.JWKSet(TestTokens.KEY.toPublicJWK()).toString()));
        server.start();
    }

    /** One server for the whole test run, expecting the key of the test application.properties. */
    public static synchronized FakeServices shared() {
        if (shared == null) {
            shared = new FakeServices(OrderServiceTestBase.API_KEY);
        }
        return shared;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Back to empty services that answer normally. */
    public synchronized void reset() {
        accounts.clear();
        products.clear();
        merchants.clear();
        fixed.clear();
        delays.clear();
        calls.clear();
        whileAnswering = null;
    }

    /** {@code hook} runs on the server's thread while each request is being answered, until reset. */
    public void whileAnswering(java.util.function.Consumer<String> hook) {
        this.whileAnswering = hook;
    }

    public void givenAccount(long id, String token, boolean buyerActive, Long merchantId) {
        accounts.put(id, new Account(token, buyerActive, merchantId));
    }

    public void givenProduct(long id, long merchantId, String name, String price, boolean active,
                             boolean merchantActive, String stockRow) {
        products.put(id, new Product(merchantId, name, new BigDecimal(price), active, merchantActive, stockRow));
    }

    public void givenMerchant(long id, boolean active, String commissionRate) {
        merchants.put(id, new Merchant(active, commissionRate));
    }

    /** Every request to {@code path} answers {@code status} with {@code body} (JSON), until reset. */
    public void answer(String path, int status, String body) {
        fixed.put(path, new Fixed(status, body));
    }

    /** Every answer on {@code path} waits this long first. */
    public void answerAfter(String path, Duration delay) {
        delays.put(path, delay);
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<Call> calls(String path) {
        return calls.stream().filter(call -> call.path().equals(path)).toList();
    }

    private void handle(HttpExchange exchange, String path) throws IOException {
        String uri = exchange.getRequestURI().toString();
        String key = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (this) {
            calls.add(new Call(path, uri, key, authorization));
        }
        java.util.function.Consumer<String> hook = whileAnswering;
        if (hook != null) {
            hook.accept(path);
        }
        Duration delay = delays.get(path);
        if (delay != null) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        Fixed answer = fixed.get(path);
        if (answer != null) {
            respond(exchange, answer.status(), answer.body());
            return;
        }
        switch (path) {
            case ACCOUNTS -> account(exchange, authorization);
            case PRODUCTS -> internal(exchange, key, this::productsBody);
            default -> internal(exchange, key, this::merchantsBody);
        }
    }

    private void account(HttpExchange exchange, String authorization) throws IOException {
        String path = exchange.getRequestURI().getPath();
        long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        Account account = accounts.get(id);
        if (account == null) {
            respond(exchange, 404, "{\"status\":404,\"detail\":\"No account\"}");
            return;
        }
        if (!("Bearer " + account.token()).equals(authorization)) {
            respond(exchange, 401, "");
            return;
        }
        respond(exchange, 200, "{\"id\":" + id + ",\"email\":\"buyer-" + id + "@example.com\",\"buyerActive\":"
                + account.buyerActive() + ",\"merchantId\":" + account.merchantId() + ",\"merchantActive\":"
                + (account.merchantId() != null) + "}");
    }

    private void internal(HttpExchange exchange, String key, java.util.function.Function<List<Long>, String> body)
            throws IOException {
        if (!apiKey.equals(key)) {
            respond(exchange, 401, "");
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        List<Long> ids = Arrays.stream(query.substring(query.indexOf("ids=") + 4).split(","))
                .map(Long::parseLong).distinct().sorted().toList();
        respond(exchange, 200, "{\"content\":[" + body.apply(ids) + "]}");
    }

    private String productsBody(List<Long> ids) {
        return ids.stream().filter(products::containsKey).map(id -> {
            Product product = products.get(id);
            return "{\"id\":" + id + ",\"merchantId\":" + product.merchantId() + ",\"name\":\"" + product.name()
                    + "\",\"price\":" + product.price().toPlainString() + ",\"active\":" + product.active()
                    + ",\"merchantActive\":" + product.merchantActive() + ",\"stockRow\":\"" + product.stockRow()
                    + "\"}";
        }).collect(Collectors.joining(","));
    }

    private String merchantsBody(List<Long> ids) {
        return ids.stream().filter(merchants::containsKey).map(id -> {
            Merchant merchant = merchants.get(id);
            return "{\"id\":" + id + ",\"active\":" + merchant.active() + ",\"commissionRate\":"
                    + merchant.commissionRate() + "}";
        }).collect(Collectors.joining(","));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    /** One request as it arrived: the path it was routed by, the full URI, the API key and Authorization headers. */
    public record Call(String path, String uri, String apiKey, String authorization) {
    }

    private record Account(String token, boolean buyerActive, Long merchantId) {
    }

    private record Product(long merchantId, String name, BigDecimal price, boolean active, boolean merchantActive,
                           String stockRow) {
    }

    private record Merchant(boolean active, String commissionRate) {
    }

    private record Fixed(int status, String body) {
    }
}
