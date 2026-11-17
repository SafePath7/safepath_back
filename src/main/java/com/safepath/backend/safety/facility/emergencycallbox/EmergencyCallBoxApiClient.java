package com.safepath.backend.safety.facility.emergencycallbox;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EmergencyCallBoxApiClient {
    private static final String ENDPOINT = "https://apis.data.go.kr/1741000/emergency_call_box_info/info";

    private final RestClient restClient;
    private final String serviceKey;

    public EmergencyCallBoxApiClient(@Value("${SAFEPATH_EMERGENCY_CALL_BOX_API_KEY:}") String serviceKey) {
        this.restClient = RestClient.create();
        this.serviceKey = serviceKey;
    }

    public EmergencyCallBoxApiResponse fetch(int pageNo, int numOfRows) {
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("Emergency call box API key is not configured");
        }

        URI requestUri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("serviceKey", serviceKey)
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .queryParam("returnType", "json")
                .build()
                .encode()
                .toUri();

        EmergencyCallBoxApiResponse response = restClient.get()
                .uri(requestUri)
                .retrieve()
                .body(EmergencyCallBoxApiResponse.class);
        if (response == null) {
            throw new IllegalStateException("Emergency call box API returned an empty response");
        }
        return response;
    }
}
