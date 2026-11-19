package com.safepath.backend.safety.facility.cctv;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CctvApiResponse(Response response) {
    public record Response(Body body, Header header) {
    }

    public record Header(String resultCode, String resultMsg) {
    }

    public record Body(String dataType, Items items, int numOfRows, int pageNo, int totalCount) {
    }

    public record Items(List<Item> item) {
    }

    public record Item(
            @JsonProperty("MNG_NO") String managementNumber,
            @JsonProperty("WGS84_LAT") String latitude,
            @JsonProperty("WGS84_LOT") String longitude,
            @JsonProperty("LCTN_ROAD_NM_ADDR") String roadAddress,
            @JsonProperty("LCTN_LOTNO_ADDR") String lotNumberAddress) {
    }
}
