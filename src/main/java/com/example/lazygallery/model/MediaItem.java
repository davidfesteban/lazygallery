package com.example.lazygallery.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MediaItem {

    String id;
    String galleryId;
    String name;
    String type;
    String mime;
    long size;
    long mtime;
    boolean shared;
    String shareLink;
    String originalUrl;
    String previewUrl;
}
