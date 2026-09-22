package com.homes.zipsai.common.service;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.homes.zipsai.common.config.StorageProperties;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Service
public class S3StorageService {
    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties storageProperties;

    public S3StorageService(S3Client client, S3Presigner presigner, StorageProperties storageProperties) {
        this.client = client;
        this.presigner = presigner;
        this.storageProperties = storageProperties;
    }

    public PresignedUpload prepareUpload(String key, String contentType, Duration ttl) {
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(storageProperties.uploadBucket())
                .key(key)
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest request = presigner.presignPutObject(
                PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(putObject).build());
        return new PresignedUpload(request.url().toString(), contentType);
    }

    public PresignedDownload prepareDownload(String key, Duration ttl) {
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(storageProperties.uploadBucket()).key(key).build();
        PresignedGetObjectRequest request = presigner.presignGetObject(
                GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(getObject).build());
        return new PresignedDownload(request.url().toString());
    }

    public ObjectMetadata head(String key) {
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(storageProperties.uploadBucket()).key(key).build());
            return new ObjectMetadata(response.contentLength(), response.contentType());
        } catch (SdkException exception) {
            return null;
        }
    }

    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(storageProperties.uploadBucket()).key(key).build());
    }

    public record PresignedUpload(String url, String contentType) {
    }

    public record PresignedDownload(String url) {
    }

    public record ObjectMetadata(long size, String contentType) {
    }
}
