package com.safepath.backend.safety.securitylight;

import java.util.List;

public record SecurityLightApiResponse(Header header, Body body) {
    public record Header(String resultCode, String resultMsg) {
    }

    public record Body(Items items, int numOfRows, int pageNo, int totalCount) {
    }

    public record Items(List<Item> item) {
    }

    public record Item(
            String lmpLcNm,
            String installationCo,
            String rdnmadr,
            String lnmadr,
            String latitude,
            String longitude,
            String installationYear,
            String installationType,
            String phoneNumber,
            String institutionNm,
            String referenceDate,
            String insttCode,
            String insttNm) {
    }
}
