package com.example.lazygallery.controller;

import com.example.lazygallery.model.MediaPage;
import com.example.lazygallery.persistence.document.MediaAssetDocument;
import com.example.lazygallery.service.MediaService;
import io.minio.errors.MinioException;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class MediaController {

    private static final String OWNER_HEADER = "X-Owner-Id";
    private static final String PASSWORD_HEADER = "X-Gallery-Password";

    private final MediaService mediaService;

    @GetMapping("/galleries/{galleryId}/media")
    public MediaPage listOwnerMedia(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return mediaService.listMediaForOwner(galleryId, ownerId, offset, limit);
    }

    @GetMapping("/shared/{shareSlug}/media")
    public MediaPage listSharedMedia(
        @PathVariable String shareSlug,
        @RequestHeader(PASSWORD_HEADER) String password,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return mediaService.listSharedMedia(shareSlug, password, offset, limit);
    }

    @PostMapping(path = "/galleries/{galleryId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId,
        @RequestParam("files") MultipartFile[] files
    ) throws Exception {
        List<String> uploaded = mediaService.uploadFiles(galleryId, ownerId, files);
        return ResponseEntity.ok(new UploadResponse(uploaded));
    }

    @GetMapping("/galleries/{galleryId}/download")
    public ResponseEntity<InputStreamResource> downloadOwnerGallery(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) throws Exception {
        return mediaService.downloadArchive(galleryId, ownerId, ifNoneMatch);
    }

    @DeleteMapping("/galleries/{galleryId}/media/{id}")
    public ResponseEntity<Void> deleteMedia(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId,
        @PathVariable String id
    ) throws Exception {
        mediaService.deleteMedia(galleryId, ownerId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/galleries/{galleryId}/media/{id}/sharing")
    public ResponseEntity<ShareResponse> updateSharing(
        @PathVariable String galleryId,
        @RequestHeader(OWNER_HEADER) String ownerId,
        @PathVariable String id,
        @RequestBody ShareRequest request
    ) {
        MediaAssetDocument updated = mediaService.updateMediaSharing(galleryId, ownerId, id, request.shared());
        String shareLink = null;
        if (updated.isShared() && updated.getShareSlug() != null) {
            shareLink = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/shared/")
                .path(updated.getShareSlug())
                .path("/files/original/")
                .path(id)
                .build()
                .toUriString();
        }
        return ResponseEntity.ok(new ShareResponse(updated.isShared(), updated.getShareSlug(), shareLink));
    }

    @GetMapping("/galleries/{galleryId}/files/original/{id}")
    public ResponseEntity<InputStreamResource> getOwnerOriginal(
        @PathVariable String galleryId,
        @PathVariable String id,
        @RequestHeader(OWNER_HEADER) String ownerId
    ) throws Exception {
        StatObjectResponse stat = mediaService.statOriginalForOwner(galleryId, ownerId, id);
        InputStream stream = mediaService.openOriginalForOwner(galleryId, ownerId, id);
        return buildStreamResponse(stat, stream);
    }

    @GetMapping("/galleries/{galleryId}/files/preview/{id}")
    public ResponseEntity<InputStreamResource> getOwnerPreview(
        @PathVariable String galleryId,
        @PathVariable String id,
        @RequestHeader(OWNER_HEADER) String ownerId
    ) throws Exception {
        StatObjectResponse stat = mediaService.statThumbnailForOwner(galleryId, ownerId, id);
        InputStream stream = mediaService.openThumbnailForOwner(galleryId, ownerId, id);
        return buildPreviewResponse(stat, stream);
    }

    @GetMapping("/shared/{shareSlug}/files/original/{id}")
    public ResponseEntity<InputStreamResource> getSharedOriginal(
        @PathVariable String shareSlug,
        @PathVariable String id,
        @RequestHeader(PASSWORD_HEADER) String password
    ) throws Exception {
        StatObjectResponse stat = mediaService.statOriginalShared(shareSlug, password, id);
        InputStream stream = mediaService.openOriginalShared(shareSlug, password, id);
        return buildStreamResponse(stat, stream);
    }

    @GetMapping("/shared/{shareSlug}/files/preview/{id}")
    public ResponseEntity<InputStreamResource> getSharedPreview(
        @PathVariable String shareSlug,
        @PathVariable String id,
        @RequestHeader(PASSWORD_HEADER) String password
    ) throws Exception {
        StatObjectResponse stat = mediaService.statThumbnailShared(shareSlug, password, id);
        InputStream stream = mediaService.openThumbnailShared(shareSlug, password, id);
        return buildPreviewResponse(stat, stream);
    }

    private ResponseEntity<InputStreamResource> buildStreamResponse(StatObjectResponse stat, InputStream stream) {
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (stat.contentType() != null) {
                mediaType = MediaType.parseMediaType(stat.contentType());
            }
        } catch (Exception ignored) {
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofHours(1)).cachePublic().getHeaderValue())
            .header(HttpHeaders.ETAG, stat.etag())
            .contentType(mediaType)
            .contentLength(stat.size())
            .body(new InputStreamResource(stream));
    }

    private ResponseEntity<InputStreamResource> buildPreviewResponse(StatObjectResponse stat, InputStream stream) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofHours(1)).cachePublic().getHeaderValue())
            .header(HttpHeaders.ETAG, stat.etag())
            .contentType(MediaType.IMAGE_JPEG)
            .contentLength(stat.size())
            .body(new InputStreamResource(stream));
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler({MinioException.class})
    public ResponseEntity<ErrorResponse> handleMinio(MinioException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("storage_error", ex.getMessage()));
    }

    @ExceptionHandler({Exception.class})
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("error", ex.getMessage()));
    }

    public record UploadResponse(List<String> uploaded) {}

    public record ShareRequest(boolean shared) {}

    public record ShareResponse(boolean shared, String shareSlug, String shareLink) {}

    public record ErrorResponse(String error, String message) {}
}
