package com.example.lazygallery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class LazyGalleryApplication {

    public static void main(String[] args) {
        SpringApplication.run(LazyGalleryApplication.class, args);
    }
}
