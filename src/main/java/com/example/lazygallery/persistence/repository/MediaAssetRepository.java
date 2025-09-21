package com.example.lazygallery.persistence.repository;

import com.example.lazygallery.persistence.document.MediaAssetDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends MongoRepository<MediaAssetDocument, String> {

    List<MediaAssetDocument> findByGalleryIdOrderByUploadedAtDesc(String galleryId);

    org.springframework.data.domain.Page<MediaAssetDocument> findByGalleryIdOrderByUploadedAtDesc(String galleryId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<MediaAssetDocument> findByGalleryIdAndSharedTrueOrderByUploadedAtDesc(String galleryId, org.springframework.data.domain.Pageable pageable);

    Optional<MediaAssetDocument> findByShareSlug(String shareSlug);
}
