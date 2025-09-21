package com.example.lazygallery.persistence.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mediaAssets")
public class MediaAssetDocument {

    @Id
    private String id;

    @Indexed
    private String galleryId;

    @Indexed
    private String ownerId;

    private String objectKey;
    private String storageName;
    private String originalName;
    private String mimeType;
    private long size;
    private Instant uploadedAt;
    private boolean shared;

    @Indexed(unique = true, sparse = true)
    private String shareSlug;
}
