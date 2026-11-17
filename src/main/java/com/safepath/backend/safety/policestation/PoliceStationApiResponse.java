package com.safepath.backend.safety.policestation;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PoliceStationApiResponse(Header header, Body body) {
    public record Header(String resultCode, String resultMsg) {
    }

    public record Body(Items items, int numOfRows, int pageNo, int totalCount) {
    }

    public record Items(List<Item> item) {
    }

    public record Item(
            @JsonProperty("objt_id") String objectId,
            @JsonProperty("fclty_cd") String facilityCode,
            @JsonProperty("fclty_nm") String facilityName,
            @JsonProperty("adres") String address,
            @JsonProperty("rn_adres") String roadAddress,
            @JsonProperty("x") String longitude,
            @JsonProperty("y") String latitude) {
    }
}
