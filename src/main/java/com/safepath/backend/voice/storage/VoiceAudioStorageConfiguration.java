package com.safepath.backend.voice.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.safepath.backend.voice.storage.VoiceAudioStorageException.Reason.INVALID_CONFIGURATION;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "safepath.voice.audio.storage.enabled", havingValue = "true")
class VoiceAudioStorageConfiguration {
    @Bean
    VoiceAudioStorage voiceAudioStorage(
            @Value("${safepath.voice.audio.storage.type:local}") String type,
            @Value("${safepath.voice.audio.storage.path:}") String path,
            @Value("${safepath.voice.audio.encryption.key:}") String key) {
        if (!"local".equals(type)) throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
        return new LocalEncryptedVoiceAudioStorage(path, new VoiceAudioEncryption(key));
    }
}
