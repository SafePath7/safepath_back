package com.safepath.backend.safety.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "safety_facilities")
public class SafetyFacility {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private double latitude;
    @Column(nullable = false)
    private double longitude;
    @Column(nullable = false, length = 255)
    private String address;
    @Column(name = "facility_type", nullable = false, length = 50)
    private String facilityType;
    @Column(length = 30)
    private String source;
    @Column(name = "external_id", length = 255)
    private String externalId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private SafetyFacility(String source, String externalId, double latitude, double longitude,
            String address, String facilityType) {
        this.source = source;
        this.externalId = externalId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.facilityType = facilityType;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public static SafetyFacility create(String source, String externalId, double latitude, double longitude,
            String address, String facilityType) {
        return new SafetyFacility(source, externalId, latitude, longitude, address, facilityType);
    }

    public void update(double latitude, double longitude, String address, String facilityType) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.facilityType = facilityType;
        this.updatedAt = LocalDateTime.now();
    }
}
