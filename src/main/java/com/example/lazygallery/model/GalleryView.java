package com.example.lazygallery.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GalleryView {

    String id;
    String name;
    boolean shared;
    String shareLink;
}
