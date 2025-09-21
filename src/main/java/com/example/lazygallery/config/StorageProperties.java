package com.example.lazygallery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private boolean secure = false;
    private String region;
    private String bucketMedia = "lazygallery-media";
    private String bucketThumbnails = "lazygallery-thumbnails";
    private String bucketArchives = "lazygallery-archives";
    private int thumbnailWidth = 512;
    private int thumbnailHeight = 512;
    private int thumbnailQuality = 80;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucketMedia() {
        return bucketMedia;
    }

    public void setBucketMedia(String bucketMedia) {
        this.bucketMedia = bucketMedia;
    }

    public String getBucketThumbnails() {
        return bucketThumbnails;
    }

    public void setBucketThumbnails(String bucketThumbnails) {
        this.bucketThumbnails = bucketThumbnails;
    }

    public String getBucketArchives() {
        return bucketArchives;
    }

    public void setBucketArchives(String bucketArchives) {
        this.bucketArchives = bucketArchives;
    }

    public int getThumbnailWidth() {
        return thumbnailWidth;
    }

    public void setThumbnailWidth(int thumbnailWidth) {
        this.thumbnailWidth = thumbnailWidth;
    }

    public int getThumbnailHeight() {
        return thumbnailHeight;
    }

    public void setThumbnailHeight(int thumbnailHeight) {
        this.thumbnailHeight = thumbnailHeight;
    }

    public int getThumbnailQuality() {
        return thumbnailQuality;
    }

    public void setThumbnailQuality(int thumbnailQuality) {
        this.thumbnailQuality = thumbnailQuality;
    }
}
