package com.example.lazygallery.controller;

import com.example.lazygallery.model.GalleryView;
import com.example.lazygallery.persistence.document.GalleryDocument;
import com.example.lazygallery.service.GalleryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Map;

@RestController
@RequestMapping("/api/galleries")
@RequiredArgsConstructor
@Validated
public class GalleryController {

    private static final String OWNER_HEADER = "X-Owner-Id";

    private final GalleryService galleryService;

    @PostMapping
    public ResponseEntity<GalleryView> create(@Valid @RequestBody CreateGalleryRequest request) {
        GalleryDocument document = galleryService.createGallery(request.ownerId(), request.name(), request.password(), request.shared());
        return ResponseEntity.ok(toView(document));
    }

    @GetMapping("/{galleryId}")
    public ResponseEntity<GalleryView> getGallery(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId
    ) {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        return ResponseEntity.ok(toView(gallery));
    }

    @PatchMapping("/{galleryId}/sharing")
    public ResponseEntity<GalleryView> updateSharing(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId,
        @Valid @RequestBody UpdateGallerySharingRequest request
    ) {
        GalleryDocument updated = galleryService.updateSharing(galleryId, ownerId, request.shared());
        return ResponseEntity.ok(toView(updated));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    private GalleryView toView(GalleryDocument document) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return galleryService.toView(document, baseUrl);
    }

    public record CreateGalleryRequest(
        @NotBlank String ownerId,
        @NotBlank String name,
        @NotBlank String password,
        boolean shared
    ) {
    }

    public record UpdateGallerySharingRequest(boolean shared) {
    }
}
