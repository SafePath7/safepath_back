package com.safepath.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    private String firebaseUid;
    @Column(nullable = false, length = 100)
    @Getter
    private String name;
    @Column(name = "phone_hash", nullable = false, unique = true, length = 64)
    private String phoneHash;
    @Column(name = "phone_encrypted", nullable = false, columnDefinition = "text")
    @Getter
    private String phoneEncrypted;
    @Column(nullable = false, unique = true, length = 255)
    @Getter
    private String email;
    // Insert and update timestamps are managed by existing SQL and database defaults.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
    @Column(name = "fcm_token", columnDefinition = "text", insertable = false, updatable = false)
    private String fcmToken;

    public void changeName(String name) {
        this.name = name;
    }
}
