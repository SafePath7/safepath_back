package com.safepath.backend.safety.facility.cctv;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class CctvApiClient {
    private static final String ENDPOINT = "https://apis.data.go.kr/1741000/cctv_info/info";

    private final RestClient restClient;
    private final String serviceKey;

    public CctvApiClient(@Value("${SAFEPATH_CCTV_API_KEY:}") String serviceKey) {
        this.restClient = RestClient.create();
        this.serviceKey = serviceKey;
    }

    public CctvApiResponse fetch(int pageNo, int numOfRows) {
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("CCTV API key is not configured");
        }

        URI requestUri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("serviceKey", serviceKey)
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .queryParam("returnType", "json")
                .build()
                .encode()
                .toUri();

        CctvApiResponse response = restClient.get()
                .uri(requestUri)
                .retrieve()
                .body(CctvApiResponse.class);
        if (response == null) {
            throw new IllegalStateException("CCTV API returned an empty response");
        }
        return response;
    }
}
