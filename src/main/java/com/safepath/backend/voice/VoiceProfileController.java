package com.safepath.backend.voice;

import com.safepath.backend.common.auth.CurrentUser;
import com.safepath.backend.voice.dto.VoiceProfileResponse;
import com.safepath.backend.voice.exception.VoiceProfileException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoiceProfileController {
    private final CurrentUser currentUser;
    private final VoiceProfileService voiceProfiles;

    public VoiceProfileController(CurrentUser currentUser, VoiceProfileService voiceProfiles) {
        this.currentUser = currentUser;
        this.voiceProfiles = voiceProfiles;
    }

    @GetMapping("/api/voice-profiles/me")
    public VoiceProfileResponse getVoiceProfile(Authentication authentication) {
        long userId = currentUser.resolve(authentication).id();
        try {
            var profile = voiceProfiles.getVoiceProfile(userId);
            return new VoiceProfileResponse(true, profile.consentedAt(), profile.createdAt(), profile.updatedAt());
        } catch (VoiceProfileException exception) {
            if (exception.reason() == VoiceProfileException.Reason.NOT_FOUND) {
                return new VoiceProfileResponse(false, null, null, null);
            }
            throw exception;
        }
    }
}
