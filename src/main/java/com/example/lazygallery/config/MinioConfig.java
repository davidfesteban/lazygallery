package com.example.lazygallery.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(StorageProperties properties) throws Exception {
        MinioClient.Builder builder = MinioClient.builder()
            .endpoint(properties.getEndpoint())
            .credentials(properties.getAccessKey(), properties.getSecretKey());
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(properties.getRegion());
        }
        MinioClient client = builder.build();

        ensureBucket(client, properties.getBucketMedia());
        ensureBucket(client, properties.getBucketThumbnails());
        ensureBucket(client, properties.getBucketArchives());

        return client;
    }

    private void ensureBucket(MinioClient client, String name) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(name).build());
        if (!exists) {
            log.info("Creating MinIO bucket {}", name);
            MakeBucketArgs.Builder builder = MakeBucketArgs.builder().bucket(name);
            if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
                builder.region(properties.getRegion());
            }
            client.makeBucket(builder.build());
        }
    }
}
