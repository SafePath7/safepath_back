package com.safepath.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class FirebaseConfig {

    // 토큰 경로를 가져와 저장
    @Value("${GOOGLE_APPLICATION_CREDENTIALS}")
    private String credentialsPath;

    // json 정보 읽기
    // 토큰 정보로 firebase 앱 초기화
    @PostConstruct
    public void initialize() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            try (FileInputStream input = new FileInputStream(credentialsPath)) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(input))
                        .build();

                FirebaseApp.initializeApp(options);
            }
        }
    }
}
