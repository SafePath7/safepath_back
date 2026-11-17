package com.safepath.backend.safety.securitylight;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SecurityLightApiClient {
    private static final String ENDPOINT = "https://api.data.go.kr/openapi/tn_pubr_public_scrty_lmp_api";

    private final RestClient restClient;
    private final String serviceKey;

    public SecurityLightApiClient(@Value("${SAFEPATH_SECURITY_LIGHT_API_KEY:}") String serviceKey) {
        this.restClient = RestClient.create();
        this.serviceKey = serviceKey;
    }

    public SecurityLightApiResponse fetch(int pageNo, int numOfRows) {
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("Security light API key is not configured");
        }

        SecurityLightApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(ENDPOINT)
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("pageNo", pageNo)
                        .queryParam("numOfRows", numOfRows)
                        .queryParam("type", "json")
                        .build())
                .retrieve()
                .body(SecurityLightApiResponse.class);
        if (response == null) {
            throw new IllegalStateException("Security light API returned an empty response");
        }
        return response;
    }
}
