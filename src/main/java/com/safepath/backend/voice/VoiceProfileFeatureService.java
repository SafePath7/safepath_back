package com.safepath.backend.voice;

import com.safepath.backend.voice.dto.VoiceProfileFeaturesResult;
import org.springframework.stereotype.Service;

/** Internal lookup. The caller must supply a verified member ID. */
@Service
public class VoiceProfileFeatureService {
    private final VoiceProfileService profiles;

    public VoiceProfileFeatureService(VoiceProfileService profiles) {
        this.profiles = profiles;
    }

    public VoiceProfileFeaturesResult getFeatures(Long userId) {
        // The delegated lookup owns the read-only transaction and the existing missing-profile policy.
        var profile = profiles.getVoiceProfile(userId);
        return new VoiceProfileFeaturesResult(profile.speakerEmbedding(), profile.baselineVolume(),
                profile.baselinePitch(), profile.modelVersion(), profile.baselineDetails());
    }
}
