package com.homes.zipsai.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .build();
    }

    private static AwsCredentialsProvider credentialsProvider(StorageProperties properties) {
        String profile = properties.awsProfile();
        return profile == null || profile.isBlank()
                ? DefaultCredentialsProvider.create()
                : ProfileCredentialsProvider.create(profile);
    }
}
