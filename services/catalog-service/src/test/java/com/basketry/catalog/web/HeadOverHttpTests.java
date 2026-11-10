package com.basketry.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.catalog.support.CatalogServiceTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * HEAD on the public reads over a real port: 200 and no body, with or without
 * a garbage bearer token. MockMvc keeps the body that Tomcat drops for HEAD,
 * so only a real server shows that none is sent. Mutation: remove HEAD from
 * the public matcher (each would be a 401).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeadOverHttpTests extends CatalogServiceTestBase {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void headOnThePublicReadsIs200WithNoBody() throws Exception {
        long tea = givenProduct(MERCHANT, "Tea").id();

        for (String path : List.of("/products", "/products/" + tea)) {
            for (String authorization : List.of("", "Bearer garbage")) {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody());
                if (!authorization.isEmpty()) {
                    request.header("Authorization", authorization);
                }

                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).as(path + " " + authorization).isEqualTo(200);
                assertThat(response.body()).as(path + " " + authorization).isEmpty();
            }
        }
    }
}
