package com.safepath.backend.risk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EventMediaStorageConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocalEventMediaStorage.class, S3MediaConfiguration.class);

    @Test
    void omittedProviderSelectsOnlyLocalStorageWithoutS3Settings() {
        assertOnlyLocalStorage(contextRunner);
    }

    @Test
    void localProviderSelectsOnlyLocalStorageWithoutS3Settings() {
        assertOnlyLocalStorage(contextRunner.withPropertyValues("safepath.media.provider=local"));
    }

    @Test
    void s3ProviderSelectsOnlyS3StorageAndClosesItsClient() {
        var client = mock(S3Client.class);
        var builder = mock(S3ClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(client);

        // Exercise the production bean factory without credentials or AWS requests.
        try (var factory = mockStatic(S3Client.class)) {
            factory.when(S3Client::builder).thenReturn(builder);
            contextRunner.withPropertyValues(
                    "safepath.media.provider=s3",
                    "safepath.media.s3.bucket=safepath-test-media")
                    .run(context -> {
                        assertThat(context).hasNotFailed()
                                .hasSingleBean(EventMediaStorage.class)
                                .hasSingleBean(S3Client.class)
                                .doesNotHaveBean(LocalEventMediaStorage.class);
                        assertThat(context.getBean(EventMediaStorage.class)).isInstanceOf(S3EventMediaStorage.class);
                        assertThat(context.getBean(S3Client.class)).isSameAs(client);
                        verifyNoInteractions(client);
                    });
            factory.verify(S3Client::builder);
        }

        verify(builder).region(Region.of("ap-northeast-2"));
        verify(client).close();
        verifyNoMoreInteractions(client);
    }

    private void assertOnlyLocalStorage(ApplicationContextRunner runner) {
        try (var factory = mockStatic(S3Client.class)) {
            runner.run(context -> {
                assertThat(context).hasNotFailed()
                        .hasSingleBean(EventMediaStorage.class)
                        .hasSingleBean(LocalEventMediaStorage.class)
                        .doesNotHaveBean(S3EventMediaStorage.class)
                        .doesNotHaveBean(S3Client.class);
                assertThat(context.getBean(EventMediaStorage.class)).isInstanceOf(LocalEventMediaStorage.class);
            });
            factory.verifyNoInteractions();
        }
    }
}
