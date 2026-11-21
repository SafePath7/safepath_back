package com.safepath.backend.voice;

import com.safepath.backend.common.auth.CurrentUser;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.voice.dto.VoiceProfileFeaturesResult;
import com.safepath.backend.voice.dto.VoiceProfileRegistration;
import com.safepath.backend.voice.dto.VoiceProfileRegistration.AudioInput;
import com.safepath.backend.voice.dto.VoiceProfileResponse;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.security.InternalAiKeyVerifier;
import com.safepath.backend.voice.storage.VoiceAudioStorageException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class VoiceProfileController {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceProfileController.class);
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final JsonMapper JSON = new JsonMapper();
    private final CurrentUser currentUser;
    private final VoiceProfileService voiceProfiles;
    private final VoiceProfileFeatureService features;
    private final InternalAiKeyVerifier internalKeyVerifier;
    private final VoiceProfileRegistrationService registration;

    public VoiceProfileController(CurrentUser currentUser, VoiceProfileService voiceProfiles,
            VoiceProfileFeatureService features, InternalAiKeyVerifier internalKeyVerifier,
            VoiceProfileRegistrationService registration) {
        this.currentUser = currentUser;
        this.voiceProfiles = voiceProfiles;
        this.features = features;
        this.internalKeyVerifier = internalKeyVerifier;
        this.registration = registration;
    }

    @PostMapping(path = "/api/voice-profiles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> registerVoiceProfile(Authentication authentication, HttpServletRequest request) {
        writeProfile(authentication, request, registration::registerNew);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping(path = "/api/voice-profiles/me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateVoiceProfile(Authentication authentication, HttpServletRequest request) {
        writeProfile(authentication, request, registration::updateExisting);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/voice-profiles/me")
    public ResponseEntity<Void> deleteVoiceProfile(Authentication authentication) {
        registration.delete(currentUser.resolve(authentication).id());
        return ResponseEntity.noContent().build();
    }

    private void writeProfile(Authentication authentication, HttpServletRequest request,
            BiConsumer<Long, VoiceProfileRegistration> operation) {
        String stage = "multipart";
        try {
            var profile = readProfile(requiredPart(request, "profile"));
            var calm = requiredPart(request, "file");
            var urgent = request.getPart("urgentFile");
            validateAudioPart(calm);
            if (urgent != null) validateAudioPart(urgent);
            try (var calmStream = calm.getInputStream();
                    var urgentStream = urgent == null ? null : urgent.getInputStream()) {
                JsonNode details = profile.path("baselineDetails");
                var command = new VoiceProfileRegistration(readEmbedding(profile.path("speakerEmbedding")),
                        readNumber(profile, "baselineVolume"), readNumber(profile, "baselinePitch"),
                        profile.path("modelVersion").isString() ? profile.path("modelVersion").asString() : null,
                        details.isNull() || details.isMissingNode() ? null : details,
                        audio(calm, calmStream), urgent == null ? null : audio(urgent, urgentStream));
                stage = "resolve-member";
                long userId = currentUser.resolve(authentication).id();
                stage = "registration";
                operation.accept(userId, command);
                stage = "close-streams";
            }
        } catch (VoiceAudioStorageException failure) {
            throw switch (failure.reason()) {
                case INVALID_AUDIO -> VoiceProfileException.invalid("file");
                case TOO_LARGE -> new ApiException(HttpStatus.CONTENT_TOO_LARGE,
                        "FILE_TOO_LARGE", "Voice upload exceeds the size limit.");
                default -> new VoiceProfileException(VoiceProfileException.Reason.DATABASE_ERROR,
                        "Voice profile registration failed.");
            };
        } catch (VoiceProfileException failure) {
            throw new VoiceProfileException(failure.reason(), "Voice profile registration failed.");
        } catch (ApiException failure) {
            if (!failure.status().is5xxServerError()) throw failure;
            LOGGER.warn("Voice profile upload failed at {} ({}).", stage, failure.getClass().getSimpleName());
            throw new VoiceProfileException(VoiceProfileException.Reason.DATABASE_ERROR, "Voice profile registration failed.");
        } catch (IOException | ServletException | RuntimeException failure) {
            LOGGER.warn("Voice profile upload failed at {} ({}).", stage, failure.getClass().getSimpleName());
            throw new VoiceProfileException(VoiceProfileException.Reason.DATABASE_ERROR, "Voice profile registration failed.");
        }
    }

    private static Part requiredPart(HttpServletRequest request, String name) throws IOException, ServletException {
        Part part = request.getPart(name);
        if (part == null) throw VoiceProfileException.invalid(name);
        return part;
    }

    private static JsonNode readProfile(Part part) throws IOException {
        try (var contents = part.getInputStream()) {
            if (part.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(part.getContentType()))) {
                throw VoiceProfileException.invalid("profile");
            }
            var profile = JSON.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(contents);
            if (profile == null || !profile.isObject()) throw VoiceProfileException.invalid("profile");
            return profile;
        } catch (JacksonException | org.springframework.http.InvalidMediaTypeException failure) {
            throw VoiceProfileException.invalid("profile");
        }
    }

    private static float[] readEmbedding(JsonNode values) {
        if (!values.isArray() || values.size() != 192) throw VoiceProfileException.invalid("speakerEmbedding");
        var embedding = new float[values.size()];
        for (int index = 0; index < embedding.length; index++) {
            if (!values.get(index).isNumber()) throw VoiceProfileException.invalid("speakerEmbedding");
            embedding[index] = values.get(index).floatValue();
        }
        return embedding;
    }

    private static double readNumber(JsonNode profile, String field) {
        var value = profile.path(field);
        if (!value.isNumber()) throw VoiceProfileException.invalid(field);
        return value.doubleValue();
    }

    private static void validateAudioPart(Part part) {
        if (part.getSize() > MAX_FILE_BYTES) throw new VoiceAudioStorageException(VoiceAudioStorageException.Reason.TOO_LARGE);
        if (part.getSize() == 0) throw VoiceProfileException.invalid("file");
    }

    private static AudioInput audio(Part part, InputStream contents) {
        return new AudioInput(part.getSubmittedFileName(), part.getContentType(), part.getSize(), contents);
    }

    @GetMapping("/api/voice-profiles/me/features")
    public ResponseEntity<VoiceProfileFeaturesResult> getVoiceProfileFeatures(
            Authentication authentication, HttpServletRequest request) {
        if (!internalKeyVerifier.matches(request.getHeader(InternalAiKeyVerifier.HEADER_NAME))) {
            throw ApiException.forbidden();
        }
        long userId = currentUser.resolve(authentication).id();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(features.getFeatures(userId));
        } catch (RuntimeException failure) {
            // MVC can log resolved exceptions, so pass only the reason across the HTTP boundary.
            var reason = failure instanceof VoiceProfileException voiceFailure
                    ? voiceFailure.reason() : VoiceProfileException.Reason.DATABASE_ERROR;
            throw new VoiceProfileException(reason, "Voice profile feature lookup failed.");
        }
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
