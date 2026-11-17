package com.safepath.backend.safety.safetyguardianstore;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SafetyGuardianStoreApiClient {
    private static final String ENDPOINT = "https://api.data.go.kr/openapi/tn_pubr_public_female_safety_prtchouse_api";

    private final RestClient restClient;
    private final String serviceKey;

    public SafetyGuardianStoreApiClient(@Value("${SAFEPATH_SAFETY_GUARDIAN_STORE_API_KEY:}") String serviceKey) {
        this.restClient = RestClient.create();
        this.serviceKey = serviceKey;
    }

    public SafetyGuardianStoreApiResponse fetch(int pageNo, int numOfRows) {
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("Safety guardian store API key is not configured");
        }

        URI requestUri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("serviceKey", serviceKey)
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .queryParam("type", "json")
                .build()
                .encode()
                .toUri();

        SafetyGuardianStoreApiResponse response = restClient.get()
                .uri(requestUri)
                .retrieve()
                .body(SafetyGuardianStoreApiResponse.class);
        if (response == null) {
            throw new IllegalStateException("Safety guardian store API returned an empty response");
        }
        return response;
    }
}
