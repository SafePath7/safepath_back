package com.safepath.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirebaseConfig {

	@Bean
	FirebaseApp firebaseApp(
			@Value("${GOOGLE_APPLICATION_CREDENTIALS:}") String credentialsPath) throws IOException {
		if (!FirebaseApp.getApps().isEmpty()) {
			return FirebaseApp.getInstance();
		}

		if (credentialsPath.isBlank()) {
			throw new IllegalStateException("GOOGLE_APPLICATION_CREDENTIALS must point to a Firebase service-account JSON file.");
		}

		try (InputStream credentials = Files.newInputStream(Path.of(credentialsPath))) {
			FirebaseOptions options = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(credentials))
					.build();
			return FirebaseApp.initializeApp(options);
		}
	}
}
