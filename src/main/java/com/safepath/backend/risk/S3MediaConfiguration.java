package com.safepath.backend.risk;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "safepath.media.storage", havingValue = "s3", matchIfMissing = true)
public class S3MediaConfiguration {
    @Bean(destroyMethod = "close")
    S3Client eventMediaS3Client(@Value("${safepath.media.s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
                .build();
    }

    @Bean
    EventMediaStorage eventMediaStorage(S3Client eventMediaS3Client,
            @Value("${safepath.media.s3.bucket}") String bucket,
            @Value("${safepath.media.s3.region}") String region) {
        return new S3EventMediaStorage(eventMediaS3Client, bucket, region);
    }
}
