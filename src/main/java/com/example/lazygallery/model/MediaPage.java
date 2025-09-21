package com.example.lazygallery.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class MediaPage {

    List<MediaItem> items;
    Integer nextOffset;
    long total;
}
