package com.safepath.backend.risk;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import com.safepath.backend.guardianrisk.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3EventMediaStorageTests {
    private static final String BUCKET = "safepath-test-media";
    private static final String KEY = "risk-events/32127358-de7e-4cb3-aad4-5cb8db155550";
    private static final String URI = "s3://" + BUCKET + "/" + KEY;
    private static final int LIMIT = 10 * 1024 * 1024;
    private final S3Client client = mock(S3Client.class);
    private final S3EventMediaStorage storage = new S3EventMediaStorage(client, BUCKET);

    @Test
    void uploadsPrivateBytesAndReturnsStableUriAndSanitizedFilename() throws IOException {
        byte[] bytes = {1, 2, 3};
        var stored = storage.store(new MockMultipartFile("file", "..\\recording.wav", "audio/wav", bytes));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());

        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).matches("risk-events/[0-9a-f-]{36}");
        assertThat(request.getValue().contentType()).isEqualTo("audio/wav");
        assertThat(request.getValue().aclAsString()).isNull();
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).containsExactly(bytes);
        }
        assertThat(stored.key()).isEqualTo("s3://" + BUCKET + "/" + request.getValue().key());
        assertThat(stored.fileName()).isEqualTo("recording.wav");
    }

    @Test
    void missingContentTypeUsesBinaryAndFilenameLengthMatchesV1() {
        var stored = storage.store(new MockMultipartFile("file", "한".repeat(300), null, new byte[] {1}));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().contentType()).isEqualTo("application/octet-stream");
        assertThat(stored.fileName()).hasSize(255);
    }

    @Test
    void readsOriginalBytesOnlyFromConfiguredBucketAndValidatedKey() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(new byte[] {4, 5}, "audio/wav"));
        assertThat(storage.read(URI)).containsExactly((byte) 4, (byte) 5);
        verify(client).getObject(GetObjectRequest.builder().bucket(BUCKET).key(KEY).build());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "s3://other-bucket/risk-events/32127358-de7e-4cb3-aad4-5cb8db155550",
            "s3://safepath-test-media/other/32127358-de7e-4cb3-aad4-5cb8db155550",
            "s3://safepath-test-media/risk-events/../secret",
            "s3://safepath-test-media/risk-events/32127358-de7e-4cb3-aad4-5cb8db155550?versionId=x",
            "https://safepath-test-media.s3.amazonaws.com/risk-events/32127358-de7e-4cb3-aad4-5cb8db155550",
            "../local-file"
    })
    void invalidStoredIdentifiersCannotReadOrDeleteAnArbitraryObject(String uri) {
        storageFailure(() -> storage.read(uri));
        storageFailure(() -> storage.delete(uri));
        storageFailure(() -> storage.deleteWithRollback(uri));
        verifyNoInteractions(client);
    }

    @Test
    void missingObjectIs404ForDownloadAndDeletionIsIdempotent() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(s3Error(404, "NoSuchKey"));
        assertThatThrownBy(() -> storage.read(URI)).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
        storage.deleteWithRollback(URI).restore();
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void missingBucketIsAStorageErrorAndMustNotSilentlyRemoveMetadata() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(s3Error(404, "NoSuchBucket"));
        storageFailure(() -> storage.read(URI));
        storageFailure(() -> storage.deleteWithRollback(URI));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void sdkCredentialOrTransportFailureBecomesStorageError() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("transport failure"));
        storageFailure(() -> storage.read(URI));
        storageFailure(() -> storage.deleteWithRollback(URI));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void putFailureIsReportedWithoutReturningMetadata() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(s3Error(403, "AccessDenied"));
        storageFailure(() -> storage.store(new MockMultipartFile("file", new byte[] {1})));
    }

    @Test
    void deletionFailurePropagatesBeforeMetadataCanBeRemoved() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(new byte[] {1}, "audio/wav"));
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(403, "AccessDenied"));
        storageFailure(() -> storage.deleteWithRollback(URI));
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void deletionCanRestoreOriginalBytesAndContentTypeWithoutOverwritingNewObject() throws IOException {
        byte[] contents = {6, 7, 8};
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(contents, "video/mp4"));
        var deletion = storage.deleteWithRollback(URI);
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        deletion.restore();

        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(KEY);
        assertThat(request.getValue().contentType()).isEqualTo("video/mp4");
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).containsExactly(contents);
        }
    }

    @Test
    void restoreConflictIsReportedRatherThanOverwritingAnotherObject() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(new byte[] {1}, "audio/wav"));
        var deletion = storage.deleteWithRollback(URI);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Error(412, "PreconditionFailed"));
        storageFailure(deletion::restore);
    }

    @Test
    void declaredOversizedObjectIsAbortedBeforeBackupOrDelete() {
        var aborted = new boolean[1];
        var input = new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) LIMIT + 1).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[0]), () -> aborted[0] = true));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(input);
        storageFailure(() -> storage.deleteWithRollback(URI));
        assertThat(aborted[0]).isTrue();
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void actualOversizedObjectIsRejectedEvenWithoutAContentLength() {
        var input = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[LIMIT + 1])));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(input);
        storageFailure(() -> storage.read(URI));
    }

    @Test
    void truncatedObjectIsNeverReturnedOrUsedAsRollbackBackup() {
        var input = new ResponseInputStream<>(GetObjectResponse.builder().contentLength(10L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[] {1})));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(input);
        storageFailure(() -> storage.deleteWithRollback(URI));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void emptyAndOversizedUploadsDoNotContactS3() {
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", new byte[0])))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", new byte[LIMIT + 1])))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_TOO_LARGE"));
        verifyNoInteractions(client);
    }

    @Test
    void uploadSizeMismatchIsRejectedBeforeS3() {
        var file = new MockMultipartFile("file", new byte[] {1, 2}) {
            @Override public long getSize() { return 1L; }
        };
        assertThatThrownBy(() -> storage.store(file)).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "s3://bucket", "bucket/path"})
    void invalidOrMissingBucketFailsConfiguration(String bucket) {
        assertThatThrownBy(() -> new S3EventMediaStorage(client, bucket)).isInstanceOf(IllegalArgumentException.class);
    }

    private ResponseInputStream<GetObjectResponse> response(byte[] bytes, String contentType) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) bytes.length)
                .contentType(contentType).build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    private S3Exception s3Error(int status, String code) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }

    private void storageFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEDIA_STORAGE_ERROR"));
    }
}
