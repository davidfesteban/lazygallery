package com.example.lazygallery.service;

import com.example.lazygallery.model.GalleryView;
import com.example.lazygallery.persistence.document.GalleryDocument;
import com.example.lazygallery.persistence.repository.GalleryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class GalleryService {

    private final GalleryRepository galleryRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public GalleryDocument createGallery(String ownerId, String name, String password, boolean shared) {
        Assert.hasText(ownerId, "ownerId required");
        Assert.hasText(name, "name required");
        Assert.hasText(password, "password required");

        Instant now = Instant.now();
        GalleryDocument document = GalleryDocument.builder()
            .ownerId(ownerId)
            .name(name)
            .passwordHash(passwordEncoder.encode(password))
            .shareSlug(generateShareSlug())
            .shared(shared)
            .createdAt(now)
            .updatedAt(now)
            .build();

        return galleryRepository.save(document);
    }

    public GalleryDocument requireOwnerGallery(String galleryId, String ownerId) {
        return galleryRepository.findById(galleryId)
            .filter(gallery -> gallery.getOwnerId().equals(ownerId))
            .orElseThrow(() -> new IllegalArgumentException("Gallery not found"));
    }

    public GalleryDocument verifySharedGallery(String shareSlug, String password) {
        GalleryDocument gallery = galleryRepository.findByShareSlug(shareSlug)
            .filter(GalleryDocument::isShared)
            .orElseThrow(() -> new IllegalArgumentException("Gallery not available"));

        if (!passwordEncoder.matches(password, gallery.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid gallery password");
        }
        return gallery;
    }

    public GalleryDocument updateSharing(String galleryId, String ownerId, boolean shared) {
        GalleryDocument gallery = requireOwnerGallery(galleryId, ownerId);
        if (shared && !gallery.isShared() && gallery.getShareSlug() == null) {
            gallery.setShareSlug(generateShareSlug());
        }
        gallery.setShared(shared);
        gallery.setUpdatedAt(Instant.now());
        return galleryRepository.save(gallery);
    }

    public GalleryView toView(GalleryDocument document, String baseUrl) {
        return GalleryView.builder()
            .id(document.getId())
            .name(document.getName())
            .shared(document.isShared())
            .shareLink(document.isShared() ? baseUrl + "/g/" + document.getShareSlug() : null)
            .build();
    }

    private String generateShareSlug() {
        byte[] buffer = new byte[12];
        secureRandom.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
