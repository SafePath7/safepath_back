package com.safepath.backend.voice.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoiceAudioStorageConfigurationTest {
    @TempDir Path root;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VoiceAudioStorageConfiguration.class);

    @Test
    void disabledDoesNotRequireAKeyOrCreateStorage() {
        runner.run(context -> assertThat(context).doesNotHaveBean(VoiceAudioStorage.class));
        runner.withPropertyValues("safepath.voice.audio.storage.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(VoiceAudioStorage.class));
    }

    @Test
    void enabledWithValidConfigurationCreatesOneStorage() throws IOException {
        enabled().withPropertyValues(
                "safepath.voice.audio.storage.path=" + root.toRealPath(),
                "safepath.voice.audio.encryption.key=" + VoiceAudioStorageTest.TEST_KEY)
                .run(context -> assertThat(context).hasSingleBean(VoiceAudioStorage.class));
    }

    @Test
    void documentedEnvironmentVariablesEnableStorage() throws IOException {
        var variables = Map.<String, Object>of(
                "SAFEPATH_VOICE_AUDIO_STORAGE_ENABLED", "true",
                "SAFEPATH_VOICE_AUDIO_STORAGE_TYPE", "local",
                "SAFEPATH_VOICE_AUDIO_STORAGE_PATH", root.toRealPath().toString(),
                "SAFEPATH_VOICE_AUDIO_ENCRYPTION_KEY", VoiceAudioStorageTest.TEST_KEY);
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("voice-test-environment", variables)))
                .run(context -> assertThat(context).hasSingleBean(VoiceAudioStorage.class));
    }

    @Test
    void enabledRejectsDirectoryWithoutWritePermission() throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"),
                "OS does not expose POSIX permissions");
        Path directory = root.toRealPath();
        var permissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE));
            assumeTrue(!Files.isWritable(directory), "Running account can bypass directory permissions");
            enabled().withPropertyValues("safepath.voice.audio.storage.path=" + directory,
                    "safepath.voice.audio.encryption.key=" + VoiceAudioStorageTest.TEST_KEY)
                    .run(context -> assertThat(context).hasFailed());
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-base64!", "YWJj"})
    void enabledRejectsMissingMalformedOrShortKey(String key) throws IOException {
        enabled().withPropertyValues("safepath.voice.audio.storage.path=" + root.toRealPath(),
                "safepath.voice.audio.encryption.key=" + key)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledRejectsUnsupportedType() throws IOException {
        enabled().withPropertyValues("safepath.voice.audio.storage.type=s3",
                "safepath.voice.audio.storage.path=" + root.toRealPath(),
                "safepath.voice.audio.encryption.key=" + VoiceAudioStorageTest.TEST_KEY)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledRejectsRelativeMissingAndNonDirectoryPaths() throws IOException {
        Path file = Files.createTempFile(root, "not-a-directory", ".tmp");
        for (String path : new String[]{"relative", root.resolve("missing").toString(), file.toString()}) {
            enabled().withPropertyValues("safepath.voice.audio.storage.path=" + path,
                    "safepath.voice.audio.encryption.key=" + VoiceAudioStorageTest.TEST_KEY)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("safepath.voice.audio.storage.enabled=true");
    }
}
