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
@Document(collection = "galleries")
public class GalleryDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String shareSlug;

    @Indexed
    private String ownerId;

    private String name;
    private String passwordHash;
    private boolean shared;
    private Instant createdAt;
    private Instant updatedAt;
}
