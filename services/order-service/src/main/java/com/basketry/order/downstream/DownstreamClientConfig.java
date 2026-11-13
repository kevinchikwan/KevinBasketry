package com.basketry.order.downstream;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One RestClient per downstream service, on the JDK HttpClient with that
 * service's connect and read timeouts, as catalog-service's client for
 * inventory-service.
 */
@Configuration(proxyBeanMethods = false)
class DownstreamClientConfig {

    @Bean
    RestClient identityRestClient(DownstreamServices services) {
        return restClient(services.identity());
    }

    @Bean
    RestClient catalogRestClient(DownstreamServices services) {
        return restClient(services.catalog());
    }

    @Bean
    RestClient merchantRestClient(DownstreamServices services) {
        return restClient(services.merchant());
    }

    private static RestClient restClient(DownstreamServices.Endpoint endpoint) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(endpoint.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(endpoint.readTimeout());
        return RestClient.builder()
                .baseUrl(endpoint.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
