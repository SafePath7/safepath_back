package com.safepath.backend.risk;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

import com.safepath.backend.risk.RiskModels.Media;
import com.safepath.backend.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/risk-events/{eventId}/medias")
public class EventMediaController {
    private final EventMediaService service;
    private final CurrentUser users;

    public EventMediaController(EventMediaService service, CurrentUser users) { this.service = service; this.users = users; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Media uploadEventMedia(Authentication auth, @PathVariable @Positive long eventId,
            @RequestParam MultipartFile file, @RequestParam @NotBlank @Size(max = 20) String mediaType) {
        return service.uploadEventMedia(users.resolve(auth), eventId, file, mediaType);
    }

    @GetMapping
    public List<Media> getEventMedias(Authentication auth, @PathVariable @Positive long eventId) {
        return service.getEventMedias(users.resolve(auth), eventId);
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEventMedia(Authentication auth, @PathVariable @Positive long eventId,
            @PathVariable @Positive long mediaId) {
        service.deleteEventMedia(users.resolve(auth), eventId, mediaId);
    }
}
