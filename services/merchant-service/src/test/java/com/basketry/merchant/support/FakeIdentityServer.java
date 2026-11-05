package com.basketry.merchant.support;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server (the JDK's built-in HttpServer) standing in for
 * identity-service: it publishes a JWKS and, if a handler is set, answers
 * POST /internal/accounts/merchant-link. Used where the real HTTP path
 * matters: token verification against a JWKS, and the client's timeouts.
 */
public final class FakeIdentityServer implements AutoCloseable {

    private static FakeIdentityServer shared;

    private final HttpServer server;
    private volatile String jwksJson;
    private volatile boolean jwksFailing;
    private volatile HttpHandler merchantLinkHandler;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    private FakeIdentityServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Several threads, so a deliberately slow handler does not block the JWKS endpoint.
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-identity-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/.well-known/jwks.json", this::jwks);
        server.createContext("/internal/accounts/merchant-link", this::merchantLink);
        server.start();
    }

    /** A new server publishing {@code keys}. */
    public static FakeIdentityServer start(RSAKey... keys) {
        FakeIdentityServer fake = new FakeIdentityServer();
        fake.publish(keys);
        return fake;
    }

    /** One server for the whole test run, publishing {@link TestTokens#KEY}. Never changed by tests. */
    public static synchronized FakeIdentityServer shared() {
        if (shared == null) {
            shared = start(TestTokens.KEY);
        }
        return shared;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Replaces the published keys, as identity-service does when it restarts. */
    public void publish(RSAKey... keys) {
        JWK[] publicKeys = Arrays.stream(keys).map(RSAKey::toPublicJWK).toArray(JWK[]::new);
        jwksJson = new JWKSet(Arrays.asList(publicKeys)).toString();
    }

    /** While true, the JWKS endpoint answers 500. */
    public void failJwks(boolean failing) {
        jwksFailing = failing;
    }

    public int jwksRequests() {
        return jwksRequests.get();
    }

    public void onMerchantLink(HttpHandler handler) {
        merchantLinkHandler = handler;
    }

    private void jwks(HttpExchange exchange) throws IOException {
        jwksRequests.incrementAndGet();
        if (jwksFailing) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        respond(exchange, 200, "application/json", jwksJson);
    }

    private void merchantLink(HttpExchange exchange) throws IOException {
        HttpHandler handler = merchantLinkHandler;
        if (handler == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        handler.handle(exchange);
    }

    public static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
