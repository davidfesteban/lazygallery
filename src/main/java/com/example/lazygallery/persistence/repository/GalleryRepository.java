package com.example.lazygallery.persistence.repository;

import com.example.lazygallery.persistence.document.GalleryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface GalleryRepository extends MongoRepository<GalleryDocument, String> {

    Optional<GalleryDocument> findByShareSlug(String shareSlug);
}
