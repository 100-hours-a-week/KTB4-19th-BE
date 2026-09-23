package com.homes.zipsai.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String awsRegion,
        String awsProfile,
        String uploadBucket,
        int presignedUrlTtlSeconds,
        int maxFileSize
) {
    public StorageProperties {
        awsRegion = awsRegion == null ? "ap-northeast-2" : awsRegion;
        uploadBucket = uploadBucket == null ? "zipsai-prod-uploads" : uploadBucket;
        presignedUrlTtlSeconds = presignedUrlTtlSeconds <= 0 ? 300 : presignedUrlTtlSeconds;
        maxFileSize = maxFileSize <= 0 ? 10 * 1024 * 1024 : maxFileSize;
    }
}
