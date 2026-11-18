package com.safepath.backend.safety.facility.cctv;

import java.net.URI;

import com.safepath.backend.safety.facility.exception.SafetyFacilityApiRequestException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class CctvApiClient {
    private static final Logger log = LoggerFactory.getLogger(CctvApiClient.class);
    private static final String ENDPOINT = "https://apis.data.go.kr/1741000/cctv_info/info";
    private static final int RESPONSE_BODY_LOG_LIMIT = 500;

    private final RestClient restClient;
    private final String serviceKey;
    private final ObjectMapper objectMapper;

    public CctvApiClient(@Value("${SAFEPATH_CCTV_API_KEY:}") String serviceKey, ObjectMapper objectMapper) {
        this.restClient = RestClient.create();
        this.serviceKey = serviceKey;
        this.objectMapper = objectMapper;
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

        ResponseEntity<String> httpResponse;
        try {
            httpResponse = restClient.get()
                    .uri(requestUri)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            logApiFailure(pageNo, e.getStatusCode().value(), contentType(e.getResponseHeaders()),
                    e.getResponseBodyAsString(), "http-error");
            throw SafetyFacilityApiRequestException.httpFailure("CCTV", pageNo, e.getStatusCode().value(), e.getStatusText(), e);
        } catch (ResourceAccessException e) {
            throw SafetyFacilityApiRequestException.networkFailure("CCTV", pageNo, e);
        }

        String responseBody = httpResponse.getBody();
        String contentType = contentType(httpResponse.getHeaders());
        if (responseBody == null || responseBody.isBlank()) {
            logApiFailure(pageNo, httpResponse.getStatusCode().value(), contentType, responseBody, "empty-body");
            throw SafetyFacilityApiRequestException.emptyResponse("CCTV", pageNo, "body");
        }

        try {
            CctvApiResponse response = objectMapper.readValue(responseBody, CctvApiResponse.class);
            if (response == null || response.response() == null) {
                logApiFailure(pageNo, httpResponse.getStatusCode().value(), contentType, responseBody, "missing-response");
            } else {
                log.debug("CCTV API response received: page={}, httpStatus={}, contentType={}",
                        pageNo, httpResponse.getStatusCode().value(), contentType);
            }
            return response;
        } catch (JacksonException e) {
            logApiFailure(pageNo, httpResponse.getStatusCode().value(), contentType, responseBody, "dto-deserialization-failure");
            throw SafetyFacilityApiRequestException.incompleteResponse("CCTV", pageNo, "dto", e);
        }
    }

    private void logApiFailure(int pageNo, int httpStatus, String contentType, String responseBody, String reason) {
        log.warn("CCTV API diagnostic: page={}, httpStatus={}, contentType={}, reason={}, responseBodyPrefix={}",
                pageNo, httpStatus, contentType, reason, abbreviate(responseBody));
    }

    private String contentType(org.springframework.http.HttpHeaders headers) {
        return headers == null || headers.getContentType() == null ? "<missing>" : headers.getContentType().toString();
    }

    private String abbreviate(String responseBody) {
        if (responseBody == null) {
            return "<null>";
        }
        String normalized = responseBody.replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= RESPONSE_BODY_LOG_LIMIT ? normalized
                : normalized.substring(0, RESPONSE_BODY_LOG_LIMIT) + "...";
    }
}
