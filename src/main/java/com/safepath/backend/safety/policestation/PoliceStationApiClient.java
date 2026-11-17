package com.safepath.backend.safety.policestation;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PoliceStationApiClient {
    private static final String ENDPOINT = "https://safemap.go.kr/openapi2/IF_0036";

    private final RestClient restClient;
    private final String serviceKey;

    public PoliceStationApiClient(@Value("${SAFEPATH_POLICE_STATION_API_KEY:}") String serviceKey) {
        this.restClient = RestClient.create();
        this.serviceKey = serviceKey;
    }

    public PoliceStationApiResponse fetch(int pageNo, int numOfRows) {
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("Police station API key is not configured");
        }

        URI requestUri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("serviceKey", serviceKey)
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .queryParam("returnType", "json")
                .build()
                .encode()
                .toUri();

        PoliceStationApiResponse response = restClient.get()
                .uri(requestUri)
                .retrieve()
                .body(PoliceStationApiResponse.class);
        if (response == null) {
            throw new IllegalStateException("Police station API returned an empty response");
        }
        return response;
    }
}
