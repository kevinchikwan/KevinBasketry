package com.basketry.catalog.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real HTTP server standing in for inventory-service's /internal/stock
 * (DESIGN.md Section 3), so catalog-service's client runs its real HTTP path:
 * timeouts, headers, status codes and bodies.
 *
 * <p>By default it behaves as inventory-service does: the API key is required
 * (401 without it), POST creates a row at 0 (201 CREATED), repeats it for the
 * same merchant (200 ALREADY_EXISTS) or refuses another merchant (409
 * PRODUCT_OWNED_BY_OTHER_MERCHANT), and GET answers the rows it has, leaving out
 * products with none. A test can make it answer a fixed status or answer late.
 */
public final class FakeInventoryServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static FakeInventoryServer shared;

    private final HttpServer server;
    private final String apiKey;
    private final Map<Long, Row> rows = new ConcurrentHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private volatile Integer createStatus;
    private volatile Integer readStatus;
    private volatile Duration delay = Duration.ZERO;

    private FakeInventoryServer(String apiKey) {
        this.apiKey = apiKey;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-inventory-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/internal/stock", this::stock);
        server.start();
    }

    /** One server for the whole test run, expecting the key of the test application.properties. */
    public static synchronized FakeInventoryServer shared() {
        if (shared == null) {
            shared = new FakeInventoryServer(CatalogServiceTestBase.API_KEY);
        }
        return shared;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Back to an empty inventory-service that answers normally. */
    public synchronized void reset() {
        rows.clear();
        calls.clear();
        createStatus = null;
        readStatus = null;
        delay = Duration.ZERO;
    }

    /** A stock row, as if created and adjusted. */
    public void givenStock(long productId, long merchantId, long quantity) {
        rows.put(productId, new Row(merchantId, quantity));
    }

    /** Every POST answers {@code status} (with a 409's code PRODUCT_OWNED_BY_OTHER_MERCHANT), or null for normal. */
    public void answerCreatesWith(Integer status) {
        this.createStatus = status;
    }

    /** Every GET answers {@code status}, or null for normal. */
    public void answerReadsWith(Integer status) {
        this.readStatus = status;
    }

    /** Every answer waits this long first. */
    public void answerAfter(Duration delay) {
        this.delay = delay;
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<Call> calls(String method) {
        return calls.stream().filter(call -> call.method().equals(method)).toList();
    }

    public Map<Long, Long> ownerOfRows() {
        return rows.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().merchantId(),
                (a, b) -> a, TreeMap::new));
    }

    private void stock(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
        synchronized (this) {
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().toString(), key, body));
        }
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        if (!apiKey.equals(key)) {
            respond(exchange, 401, "");
            return;
        }
        if (exchange.getRequestMethod().equals("POST")) {
            create(exchange, body);
        } else {
            read(exchange);
        }
    }

    private void create(HttpExchange exchange, String body) throws IOException {
        Integer fixed = createStatus;
        if (fixed != null) {
            respond(exchange, fixed, fixed == 409
                    ? "{\"status\":409,\"code\":\"PRODUCT_OWNED_BY_OTHER_MERCHANT\"}"
                    : "{\"status\":" + fixed + ",\"detail\":\"fixed answer\"}");
            return;
        }
        JsonNode request = JSON.readTree(body);
        long productId = request.get("productId").asLong();
        long merchantId = request.get("merchantId").asLong();
        Row existing = rows.putIfAbsent(productId, new Row(merchantId, 0));
        if (existing == null) {
            respond(exchange, 201, "{\"productId\":" + productId + ",\"outcome\":\"CREATED\"}");
        } else if (existing.merchantId() == merchantId) {
            respond(exchange, 200, "{\"productId\":" + productId + ",\"outcome\":\"ALREADY_EXISTS\"}");
        } else {
            respond(exchange, 409, "{\"status\":409,\"code\":\"PRODUCT_OWNED_BY_OTHER_MERCHANT\"}");
        }
    }

    private void read(HttpExchange exchange) throws IOException {
        Integer fixed = readStatus;
        if (fixed != null) {
            respond(exchange, fixed, "{\"status\":" + fixed + "}");
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String ids = query.substring(query.indexOf("productIds=") + "productIds=".length());
        String content = Arrays.stream(ids.split(","))
                .map(Long::parseLong)
                .distinct()
                .sorted()
                .filter(rows::containsKey)
                .map(id -> "{\"productId\":" + id + ",\"merchantId\":" + rows.get(id).merchantId()
                        + ",\"quantity\":" + rows.get(id).quantity() + "}")
                .collect(Collectors.joining(","));
        respond(exchange, 200, "{\"content\":[" + content + "]}");
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

    /** One request as it arrived: the method, the path and query, the API key header (or null) and the body. */
    public record Call(String method, String uri, String apiKey, String body) {
    }

    private record Row(long merchantId, long quantity) {
    }
}
