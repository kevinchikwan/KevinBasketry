package com.basketry.catalog.inventory;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client for inventory-service: the JDK HttpClient with
 * {@code basketry.inventory.connect-timeout} and {@code read-timeout}, as
 * merchant-service's client for identity-service.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryClientConfig {

    @Bean
    RestClient inventoryRestClient(InventoryProperties inventory) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(inventory.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(inventory.readTimeout());
        return RestClient.builder()
                .baseUrl(inventory.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
