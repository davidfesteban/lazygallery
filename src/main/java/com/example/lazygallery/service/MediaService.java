package com.example.lazygallery.service;

import com.example.lazygallery.config.StorageProperties;
import com.example.lazygallery.model.MediaItem;
import com.example.lazygallery.model.MediaPage;
import com.example.lazygallery.persistence.document.GalleryDocument;
import com.example.lazygallery.persistence.document.MediaAssetDocument;
import com.example.lazygallery.persistence.repository.MediaAssetRepository;
import com.example.lazygallery.util.IdCodec;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final String GALLERIES_PREFIX = "galleries/";
    private static final String ORIGINALS_FOLDER = "originals/";
    private static final String THUMBNAILS_FOLDER = "thumbnails/";
    private static final String ARCHIVES_FOLDER = "archives/";

    private final MinioClient client;
    private final StorageProperties props;
    private final MediaAssetRepository mediaAssetRepository;
    private final GalleryService galleryService;
    private final SecureRandom secureRandom = new SecureRandom();

    public MediaPage listMediaForOwner(String galleryId, String ownerId, int offset, int limit) {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        PagedResult result = fetchMedia(gallery.getId(), offset, limit, false);
        return toMediaPage(result, gallery, true);
    }

    public MediaPage listSharedMedia(String shareSlug, String password, int offset, int limit) {
        GalleryDocument gallery = galleryService.verifySharedGallery(shareSlug, password);
        if (!gallery.isShared()) {
            throw new IllegalArgumentException("Gallery is not shared");
        }
        PagedResult result = fetchMedia(gallery.getId(), offset, limit, true);
        return toMediaPage(result, gallery, false);
    }

    public List<String> uploadFiles(String galleryId, String ownerId, MultipartFile[] files) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        List<String> stored = new ArrayList<>();
        if (files == null) {
            return stored;
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            byte[] data = file.getBytes();
            String storageName = generateObjectName(file.getOriginalFilename());
            String objectName = galleryOriginalKey(gallery.getId(), storageName);
            String contentType = resolveContentType(file);

            client.putObject(PutObjectArgs.builder()
                .bucket(props.getBucketMedia())
                .object(objectName)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType(contentType)
                .userMetadata(Map.of(
                    "original-name", Optional.ofNullable(file.getOriginalFilename()).orElse(storageName),
                    "uploaded-at", Long.toString(System.currentTimeMillis())
                ))
                .build());

            if (contentType.startsWith("image/")) {
                createThumbnail(gallery.getId(), storageName, data);
            }

            MediaAssetDocument document = MediaAssetDocument.builder()
                .galleryId(gallery.getId())
                .ownerId(ownerId)
                .objectKey(objectName)
                .storageName(storageName)
                .originalName(Optional.ofNullable(file.getOriginalFilename()).orElse(storageName))
                .mimeType(contentType)
                .size(file.getSize())
                .uploadedAt(Instant.now())
                .shared(false)
                .build();

            MediaAssetDocument saved = mediaAssetRepository.save(document);
            stored.add(IdCodec.encode(saved.getId()));
        }

        return stored;
    }

    public void deleteMedia(String galleryId, String ownerId, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        MediaAssetDocument document = resolveOwnedMedia(encodedId, gallery.getId());

        mediaAssetRepository.deleteById(document.getId());

        client.removeObject(RemoveObjectArgs.builder()
            .bucket(props.getBucketMedia())
            .object(document.getObjectKey())
            .build());

        try {
            client.removeObject(RemoveObjectArgs.builder()
                .bucket(props.getBucketThumbnails())
                .object(galleryThumbnailKey(gallery.getId(), document.getStorageName()))
                .build());
        } catch (Exception ex) {
            log.debug("No thumbnail to delete for {}", document.getStorageName());
        }
    }

    public MediaAssetDocument updateMediaSharing(String galleryId, String ownerId, String encodedId, boolean shared) {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        if (shared && !gallery.isShared()) {
            throw new IllegalArgumentException("Enable gallery sharing before sharing files");
        }
        MediaAssetDocument document = resolveOwnedMedia(encodedId, gallery.getId());
        document.setShared(shared);
        if (shared && document.getShareSlug() == null) {
            document.setShareSlug(generateShareSlug());
        }
        if (!shared) {
            document.setShareSlug(null);
        }
        return mediaAssetRepository.save(document);
    }

    public ResponseEntity<InputStreamResource> downloadArchive(String galleryId, String ownerId, String ifNoneMatch) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        List<MediaAssetDocument> inventory = new ArrayList<>(mediaAssetRepository.findByGalleryIdOrderByUploadedAtDesc(gallery.getId()));
        inventory.sort(Comparator.comparing(MediaAssetDocument::getUploadedAt).reversed());
        String signature = computeSignature(inventory);
        String etag = '"' + signature + '"';

        if (Objects.equals(etag, ifNoneMatch)) {
            return ResponseEntity.status(304)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=0, must-revalidate")
                .build();
        }

        String archiveObject = galleryArchiveKey(gallery.getId(), signature);
        ensureArchiveExists(archiveObject, inventory);

        StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
            .bucket(props.getBucketArchives())
            .object(archiveObject)
            .build());

        InputStream stream = client.getObject(GetObjectArgs.builder()
            .bucket(props.getBucketArchives())
            .object(archiveObject)
            .build());

        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=0, must-revalidate")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gallery-" + gallery.getName().replace(' ', '_') + "-" + signature.substring(0, 8) + ".zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(stat.size())
            .body(new InputStreamResource(stream));
    }

    public StatObjectResponse statOriginalForOwner(String galleryId, String ownerId, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        MediaAssetDocument document = resolveOwnedMedia(encodedId, gallery.getId());
        return statObject(props.getBucketMedia(), document.getObjectKey());
    }

    public InputStream openOriginalForOwner(String galleryId, String ownerId, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        MediaAssetDocument document = resolveOwnedMedia(encodedId, gallery.getId());
        return openObject(props.getBucketMedia(), document.getObjectKey());
    }

    public StatObjectResponse statThumbnailForOwner(String galleryId, String ownerId, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        MediaAssetDocument document = resolveOwnedMedia(encodedId, gallery.getId());
        return statObject(props.getBucketThumbnails(), galleryThumbnailKey(gallery.getId(), document.getStorageName()));
    }

    public InputStream openThumbnailForOwner(String galleryId, String ownerId, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.requireOwnerGallery(galleryId, ownerId);
        MediaAssetDocument document = resolveOwnedMedia(encodedId, gallery.getId());
        return openObject(props.getBucketThumbnails(), galleryThumbnailKey(gallery.getId(), document.getStorageName()));
    }

    public StatObjectResponse statOriginalShared(String shareSlug, String password, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.verifySharedGallery(shareSlug, password);
        MediaAssetDocument document = resolveSharedMedia(encodedId, gallery);
        return statObject(props.getBucketMedia(), document.getObjectKey());
    }

    public InputStream openOriginalShared(String shareSlug, String password, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.verifySharedGallery(shareSlug, password);
        MediaAssetDocument document = resolveSharedMedia(encodedId, gallery);
        return openObject(props.getBucketMedia(), document.getObjectKey());
    }

    public StatObjectResponse statThumbnailShared(String shareSlug, String password, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.verifySharedGallery(shareSlug, password);
        MediaAssetDocument document = resolveSharedMedia(encodedId, gallery);
        return statObject(props.getBucketThumbnails(), galleryThumbnailKey(gallery.getId(), document.getStorageName()));
    }

    public InputStream openThumbnailShared(String shareSlug, String password, String encodedId) throws Exception {
        GalleryDocument gallery = galleryService.verifySharedGallery(shareSlug, password);
        MediaAssetDocument document = resolveSharedMedia(encodedId, gallery);
        return openObject(props.getBucketThumbnails(), galleryThumbnailKey(gallery.getId(), document.getStorageName()));
    }

    private PagedResult fetchMedia(String galleryId, int offset, int limit, boolean sharedOnly) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        int pageNumber = safeOffset / safeLimit;
        int localSkip = safeOffset % safeLimit;

        Sort sort = Sort.by(Sort.Direction.DESC, "uploadedAt");
        Pageable pageable = PageRequest.of(pageNumber, safeLimit, sort);
        Page<MediaAssetDocument> page = sharedOnly
            ? mediaAssetRepository.findByGalleryIdAndSharedTrueOrderByUploadedAtDesc(galleryId, pageable)
            : mediaAssetRepository.findByGalleryIdOrderByUploadedAtDesc(galleryId, pageable);

        List<MediaAssetDocument> content = page.getContent();
        if (localSkip > 0 && content.size() > localSkip) {
            content = content.subList(localSkip, content.size());
        } else if (localSkip > 0) {
            content = List.of();
        }
        if (content.size() > safeLimit) {
            content = content.subList(0, safeLimit);
        }

        long total = page.getTotalElements();
        int returned = content.size();
        Integer nextOffset = null;
        if (safeOffset + returned < total) {
            nextOffset = safeOffset + returned;
        }
        return new PagedResult(content, total, nextOffset);
    }

    private MediaPage toMediaPage(PagedResult paged, GalleryDocument gallery, boolean ownerContext) {
        List<MediaItem> items = paged.documents().stream()
            .map(doc -> toMediaItem(doc, gallery, ownerContext))
            .toList();
        return MediaPage.builder()
            .items(items)
            .nextOffset(paged.nextOffset())
            .total(paged.total())
            .build();
    }

    private MediaItem toMediaItem(MediaAssetDocument doc, GalleryDocument gallery, boolean ownerContext) {
        String encodedId = IdCodec.encode(doc.getId());
        String type = detectType(doc.getMimeType());
        String originalUrl;
        String previewUrl = null;
        if (ownerContext) {
            originalUrl = "/api/galleries/" + gallery.getId() + "/files/original/" + encodedId;
            if ("image".equals(type)) {
                previewUrl = "/api/galleries/" + gallery.getId() + "/files/preview/" + encodedId;
            }
        } else {
            originalUrl = "/api/shared/" + gallery.getShareSlug() + "/files/original/" + encodedId;
            if ("image".equals(type)) {
                previewUrl = "/api/shared/" + gallery.getShareSlug() + "/files/preview/" + encodedId;
            }
        }

        String shareLink = null;
        if (gallery.isShared() && doc.isShared() && gallery.getShareSlug() != null) {
            shareLink = "/api/shared/" + gallery.getShareSlug() + "/files/original/" + encodedId;
        }

        return MediaItem.builder()
            .id(encodedId)
            .galleryId(gallery.getId())
            .name(doc.getOriginalName())
            .type(type)
            .mime(doc.getMimeType())
            .size(doc.getSize())
            .mtime(doc.getUploadedAt().toEpochMilli())
            .shared(doc.isShared())
            .shareLink(shareLink)
            .originalUrl(originalUrl)
            .previewUrl(previewUrl)
            .build();
    }

    private MediaAssetDocument resolveOwnedMedia(String encodedId, String galleryId) {
        String mediaId = IdCodec.decode(encodedId);
        return mediaAssetRepository.findById(mediaId)
            .filter(doc -> Objects.equals(doc.getGalleryId(), galleryId))
            .orElseThrow(() -> new IllegalArgumentException("Media not found"));
    }

    private MediaAssetDocument resolveSharedMedia(String encodedId, GalleryDocument gallery) {
        String mediaId = IdCodec.decode(encodedId);
        return mediaAssetRepository.findById(mediaId)
            .filter(MediaAssetDocument::isShared)
            .filter(doc -> Objects.equals(doc.getGalleryId(), gallery.getId()))
            .orElseThrow(() -> new IllegalArgumentException("Media not available"));
    }

    private void ensureArchiveExists(String objectName, List<MediaAssetDocument> inventory) throws Exception {
        try {
            client.statObject(StatObjectArgs.builder()
                .bucket(props.getBucketArchives())
                .object(objectName)
                .build());
            return;
        } catch (Exception ex) {
            log.info("Archive {} missing, generating new version", objectName);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            byte[] chunk = new byte[8192];
            for (MediaAssetDocument meta : inventory) {
                zip.putNextEntry(new ZipEntry(FilenameUtils.getName(meta.getOriginalName())));
                try (InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(props.getBucketMedia())
                    .object(meta.getObjectKey())
                    .build())) {
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        zip.write(chunk, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }

        byte[] payload = buffer.toByteArray();
        client.putObject(PutObjectArgs.builder()
            .bucket(props.getBucketArchives())
            .object(objectName)
            .stream(new ByteArrayInputStream(payload), payload.length, -1)
            .contentType("application/zip")
            .build());
    }

    private String galleryOriginalKey(String galleryId, String storageName) {
        return GALLERIES_PREFIX + galleryId + "/" + ORIGINALS_FOLDER + storageName;
    }

    private String galleryThumbnailKey(String galleryId, String storageName) {
        return GALLERIES_PREFIX + galleryId + "/" + THUMBNAILS_FOLDER + storageName + ".jpg";
    }

    private String galleryArchiveKey(String galleryId, String signature) {
        return GALLERIES_PREFIX + galleryId + "/" + ARCHIVES_FOLDER + "media-" + signature + ".zip";
    }

    private void createThumbnail(String galleryId, String storageName, byte[] data) {
        String object = galleryThumbnailKey(galleryId, storageName);
        try {
            client.statObject(StatObjectArgs.builder()
                .bucket(props.getBucketThumbnails())
                .object(object)
                .build());
            return;
        } catch (Exception ignored) {
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(data))
                .size(props.getThumbnailWidth(), props.getThumbnailHeight())
                .outputQuality(props.getThumbnailQuality() / 100.0)
                .outputFormat("jpg")
                .toOutputStream(out);
            byte[] thumbBytes = out.toByteArray();
            client.putObject(PutObjectArgs.builder()
                .bucket(props.getBucketThumbnails())
                .object(object)
                .stream(new ByteArrayInputStream(thumbBytes), thumbBytes.length, -1)
                .contentType("image/jpeg")
                .build());
        } catch (Exception ex) {
            log.warn("Failed to build thumbnail for {}: {}", storageName, ex.getMessage());
        }
    }

    private String computeSignature(List<MediaAssetDocument> inventory) {
        String fingerprint = inventory.stream()
            .map(meta -> meta.getObjectKey() + '|' + meta.getSize() + '|' + meta.getUploadedAt().toEpochMilli())
            .collect(java.util.stream.Collectors.joining("\n"));
        return DigestUtils.sha1Hex(fingerprint);
    }

    private String generateObjectName(String originalFilename) {
        String extension = Optional.ofNullable(FilenameUtils.getExtension(Optional.ofNullable(originalFilename).orElse("")))
            .filter(StringUtils::hasText)
            .map(ext -> ext.toLowerCase(Locale.ROOT))
            .orElse("bin");
        return UUID.randomUUID() + "." + extension;
    }

    private String resolveContentType(MultipartFile file) {
        return Optional.ofNullable(file.getContentType())
            .filter(StringUtils::hasText)
            .or(() -> MediaTypeFactory.getMediaType(file.getOriginalFilename()).map(MediaType::toString))
            .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private String detectType(String mime) {
        if (mime == null) {
            return "other";
        }
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/")) {
            return "video";
        }
        return "other";
    }

    private String generateShareSlug() {
        byte[] buffer = new byte[12];
        secureRandom.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private StatObjectResponse statObject(String bucket, String object) throws Exception {
        return client.statObject(StatObjectArgs.builder()
            .bucket(bucket)
            .object(object)
            .build());
    }

    private InputStream openObject(String bucket, String object) throws Exception {
        return client.getObject(GetObjectArgs.builder()
            .bucket(bucket)
            .object(object)
            .build());
    }

    private record PagedResult(List<MediaAssetDocument> documents, long total, Integer nextOffset) {
    }
}
