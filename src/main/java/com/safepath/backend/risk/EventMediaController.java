package com.safepath.backend.risk;

import java.util.List;
import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

import com.safepath.backend.risk.RiskModels.Media;
import com.safepath.backend.guardianrisk.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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

    @GetMapping(params = "!mediaId")
    public List<Media> getEventMedias(Authentication auth, @PathVariable @Positive long eventId) {
        return service.getEventMedias(users.resolve(auth), eventId);
    }

    @GetMapping(params = "mediaId")
    public ResponseEntity<byte[]> downloadEventMedia(Authentication auth, @PathVariable @Positive long eventId,
            @RequestParam @Positive long mediaId) {
        var download = service.downloadEventMedia(users.resolve(auth), eventId, mediaId);
        String name = download.fileName();
        if (name == null || name.isBlank()) name = "download";
        // Also sanitize legacy database filenames before constructing an HTTP header.
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) name = "download";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.contents().length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .body(download.contents());
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEventMedia(Authentication auth, @PathVariable @Positive long eventId,
            @PathVariable @Positive long mediaId) {
        service.deleteEventMedia(users.resolve(auth), eventId, mediaId);
    }
}
