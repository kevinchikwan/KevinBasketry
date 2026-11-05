package com.basketry.merchant.identityclient;

import com.basketry.merchant.config.IdentityServiceProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client for identity-service: the JDK HttpClient with
 * {@code basketry.identity.connect-timeout} and {@code read-timeout}, so a
 * hung identity-service cannot hold an onboarding request open.
 */
@Configuration
public class IdentityClientConfig {

    @Bean
    RestClient identityRestClient(IdentityServiceProperties identity) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(identity.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(identity.readTimeout());
        return RestClient.builder()
                .baseUrl(identity.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
