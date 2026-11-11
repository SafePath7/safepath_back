package com.safepath.backend.risk;

import java.io.IOException;
import java.util.UUID;

import com.safepath.backend.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3EventMediaStorage implements EventMediaStorage {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final String PREFIX = "risk-events/";
    private final S3Client client;
    private final String bucket;
    private final S3Utilities utilities;

    public S3EventMediaStorage(S3Client client, String bucket, String region) {
        this.client = client;
        this.bucket = bucket;
        this.utilities = S3Utilities.builder().region(Region.of(region)).build();
    }

    @Override
    public StoredFile store(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.badRequest("빈 파일은 업로드할 수 없습니다.");
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "파일은 10MB 이하여야 합니다.");
        }
        requireBucket();
        String key = PREFIX + UUID.randomUUID();
        String url = url(key);
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try (var stream = file.getInputStream()) {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromInputStream(stream, file.getSize()));
            return new StoredFile(url, safeFileName(file.getOriginalFilename()));
        } catch (IOException | SdkException exception) {
            throw storageError();
        }
    }

    @Override
    public void delete(String storageUrl) {
        String key = key(storageUrl);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException exception) {
            throw storageError();
        }
    }

    @Override
    public Deletion deleteWithRollback(String storageUrl) {
        String key = key(storageUrl);
        byte[] contents;
        String contentType;
        try (var response = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            contents = response.readNBytes(MAX_FILE_BYTES + 1);
            contentType = response.response().contentType();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return () -> {};
            throw storageError();
        } catch (IOException | SdkException exception) {
            throw storageError();
        }
        if (contents.length > MAX_FILE_BYTES) throw storageError();
        delete(storageUrl);
        return () -> {
            try {
                // A rollback must never overwrite another object created at the same key.
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                                .contentType(contentType).ifNoneMatch("*").build(),
                        RequestBody.fromBytes(contents));
            } catch (SdkException exception) {
                throw storageError();
            }
        };
    }

    private String key(String storageUrl) {
        requireBucket();
        String base = url(PREFIX);
        if (storageUrl == null || !storageUrl.startsWith(base)) throw storageError();
        String identifier = storageUrl.substring(base.length());
        if (!identifier.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw storageError();
        }
        return PREFIX + identifier;
    }

    private String url(String key) {
        return utilities.getUrl(request -> request.bucket(bucket).key(key)).toExternalForm();
    }

    private void requireBucket() {
        if (bucket == null || bucket.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_NOT_CONFIGURED",
                    "AWS S3 버킷 설정이 필요합니다.");
        }
    }

    private String safeFileName(String original) {
        if (original == null || original.isBlank()) return "upload";
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) return "upload";
        int end = name.offsetByCodePoints(0, Math.min(name.codePointCount(0, name.length()), 255));
        return name.substring(0, end);
    }

    private ApiException storageError() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR", "AWS S3 미디어 작업에 실패했습니다.");
    }
}
