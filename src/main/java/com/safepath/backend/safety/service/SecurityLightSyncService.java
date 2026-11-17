package com.safepath.backend.safety.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.safepath.backend.safety.client.SecurityLightApiClient;
import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.dto.SecurityLightApiResponse;

import org.springframework.stereotype.Service;

@Service
public class SecurityLightSyncService {
    private static final String SOURCE = "SECURITY_LIGHT";
    private static final int PAGE_SIZE = 1_000;

    private final SecurityLightApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;

    public SecurityLightSyncService(SecurityLightApiClient apiClient, SafetyFacilitySyncService facilitySyncService) {
        this.apiClient = apiClient;
        this.facilitySyncService = facilitySyncService;
    }

    public SyncResult syncAll() {
        int createdCount = 0;
        int updatedCount = 0;
        int totalCount = 0;

        for (int pageNo = 1; ; pageNo++) {
            SecurityLightApiResponse response = apiClient.fetch(pageNo, PAGE_SIZE);
            validateResponse(response);

            List<SafetyFacilityImportItem> items = response.body().items().item().stream()
                    .map(this::toImportItem)
                    .toList();
            SafetyFacilitySyncService.SyncResult pageResult = facilitySyncService.syncAll(items);
            createdCount += pageResult.createdCount();
            updatedCount += pageResult.updatedCount();
            totalCount = response.body().totalCount();

            if (pageNo * PAGE_SIZE >= totalCount) {
                return new SyncResult(pageNo, totalCount, createdCount, updatedCount);
            }
        }
    }

    private void validateResponse(SecurityLightApiResponse response) {
        if (response.header() == null || !"00".equals(response.header().resultCode()) || response.body() == null
                || response.body().items() == null || response.body().items().item() == null) {
            throw new IllegalStateException("Security light API response is invalid");
        }
    }

    private SafetyFacilityImportItem toImportItem(SecurityLightApiResponse.Item item) {
        String address = item.rdnmadr().isBlank() ? item.lnmadr() : item.rdnmadr();
        return new SafetyFacilityImportItem(
                SOURCE,
                externalId(item),
                Double.parseDouble(item.latitude()),
                Double.parseDouble(item.longitude()),
                address,
                SOURCE);
    }

    private String externalId(SecurityLightApiResponse.Item item) {
        String value = String.join("|", item.insttCode(), item.lmpLcNm(), item.latitude(), item.longitude());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record SyncResult(int pageCount, int totalCount, int createdCount, int updatedCount) {
    }
}
