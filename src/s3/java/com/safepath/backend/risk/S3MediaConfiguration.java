package com.safepath.backend.risk;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Included by risk-media/s3.init.gradle without changing the shared build file. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "safepath.media.provider", havingValue = "s3")
public class S3MediaConfiguration {
    @Bean(destroyMethod = "close")
    S3Client eventMediaS3Client(@Value("${safepath.media.s3.region:ap-northeast-2}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                // Uses the SDK default credentials chain (including EC2/ECS IAM roles).
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
                .build();
    }

    @Bean
    EventMediaStorage eventMediaStorage(@Qualifier("eventMediaS3Client") S3Client client,
            @Value("${safepath.media.s3.bucket}") String bucket) {
        return new S3EventMediaStorage(client, bucket);
    }
}
