package com.safepath.backend.risk;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import com.safepath.backend.guardianrisk.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Optional S3 adapter; storage_url contains a stable S3 URI, never a public download URL. */
public class S3EventMediaStorage implements EventMediaStorage {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final String PREFIX = "risk-events/";
    private final S3Client client;
    private final String bucket;

    public S3EventMediaStorage(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client);
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("safepath.media.s3.bucket must be a valid S3 bucket name");
        }
        this.bucket = bucket;
    }

    @Override
    public StoredFile store(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.badRequest("빈 파일은 업로드할 수 없습니다.");
        if (file.getSize() > MAX_FILE_BYTES) throw tooLarge();
        byte[] contents;
        try (var stream = file.getInputStream()) {
            contents = stream.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException exception) {
            throw storageError();
        }
        if (contents.length > MAX_FILE_BYTES) throw tooLarge();
        if (contents.length == 0 || contents.length != file.getSize()) {
            throw ApiException.badRequest("파일 크기와 실제 업로드 내용이 일치하지 않습니다.");
        }
        String key = PREFIX + UUID.randomUUID();
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        try {
            // No public ACL is supplied; bucket access is controlled by IAM/bucket policy.
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType(contentType).ifNoneMatch("*").build(), RequestBody.fromBytes(contents));
            return new StoredFile("s3://" + bucket + "/" + key, safeFileName(file.getOriginalFilename()));
        } catch (SdkException exception) {
            throw storageError();
        }
    }

    @Override
    public byte[] read(String storageUrl) {
        return object(key(storageUrl)).contents();
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
        StoredObject original;
        try {
            original = object(key);
        } catch (ApiException exception) {
            if (exception.status() == HttpStatus.NOT_FOUND) return () -> {};
            throw exception;
        }
        delete(storageUrl);
        return () -> {
            try {
                // Do not overwrite a different object placed at the same key after deletion.
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                                .contentType(original.contentType()).ifNoneMatch("*").build(),
                        RequestBody.fromBytes(original.contents()));
            } catch (SdkException exception) {
                throw storageError();
            }
        };
    }

    private StoredObject object(String key) {
        try (var response = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            Long length = response.response().contentLength();
            if (length != null && length > MAX_FILE_BYTES) {
                response.abort();
                throw storageError();
            }
            byte[] contents = response.readNBytes(MAX_FILE_BYTES + 1);
            if (contents.length > MAX_FILE_BYTES || (length != null && length != contents.length)) {
                response.abort();
                throw storageError();
            }
            String contentType = response.response().contentType();
            return new StoredObject(contents,
                    contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        } catch (S3Exception exception) {
            if (missingObject(exception)) throw ApiException.notFound("이벤트 미디어 파일");
            throw storageError();
        } catch (IOException | SdkException exception) {
            throw storageError();
        }
    }

    private boolean missingObject(S3Exception exception) {
        String code = exception.awsErrorDetails() == null ? null : exception.awsErrorDetails().errorCode();
        return exception instanceof NoSuchKeyException
                || (exception.statusCode() == 404 && !"NoSuchBucket".equals(code));
    }

    private String key(String storageUrl) {
        String base = "s3://" + bucket + "/" + PREFIX;
        if (storageUrl == null || !storageUrl.startsWith(base)) throw storageError();
        String identifier = storageUrl.substring(base.length());
        if (!identifier.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw storageError();
        }
        return PREFIX + identifier;
    }

    private String safeFileName(String original) {
        if (original == null || original.isBlank()) return "upload";
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) return "upload";
        int end = name.offsetByCodePoints(0, Math.min(name.codePointCount(0, name.length()), 255));
        return name.substring(0, end);
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "파일은 10MB 이하여야 합니다.");
    }

    private ApiException storageError() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR", "AWS S3 미디어 작업에 실패했습니다.");
    }

    private record StoredObject(byte[] contents, String contentType) {}
}
