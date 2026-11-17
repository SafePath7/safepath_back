package com.safepath.backend.safety.safetyguardianstore;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SafetyGuardianStoreApiResponse(Header header, Body body) {
    public record Header(String resultCode, String resultMsg) {
    }

    public record Body(Items items, int totalCount) {
    }

    public record Items(List<Item> item) {
    }

    public record Item(
            @JsonProperty("storNm") String storeName,
            @JsonProperty("rdnmadr") String roadAddress,
            @JsonProperty("lnmadr") String lotAddress,
            String latitude,
            String longitude,
            String phoneNumber) {
    }
}
