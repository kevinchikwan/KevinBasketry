package com.basketry.promotion.support;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;

/**
 * A real HTTP server standing in for identity-service's JWKS endpoint, so
 * token verification runs the real HTTP path (copied from inventory-service,
 * JWKS only).
 */
public final class FakeIdentityServer {

    private static FakeIdentityServer shared;

    private final HttpServer server;
    private volatile String jwksJson;

    private FakeIdentityServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-identity-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/.well-known/jwks.json", this::jwks);
        server.start();
    }

    /** One server for the whole test run, publishing {@link TestTokens#KEY}. Never changed by tests. */
    public static synchronized FakeIdentityServer shared() {
        if (shared == null) {
            shared = new FakeIdentityServer();
            shared.publish(TestTokens.KEY);
        }
        return shared;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void publish(RSAKey... keys) {
        JWK[] publicKeys = Arrays.stream(keys).map(RSAKey::toPublicJWK).toArray(JWK[]::new);
        jwksJson = new JWKSet(Arrays.asList(publicKeys)).toString();
    }

    private void jwks(HttpExchange exchange) throws IOException {
        byte[] bytes = jwksJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
