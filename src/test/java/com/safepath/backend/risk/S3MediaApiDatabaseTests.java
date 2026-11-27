package com.safepath.backend.risk;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.common.auth.FirebaseAdminRoleLookup;
import com.safepath.backend.risk.repository.RiskEventMediaRepository;
import com.safepath.backend.risk.service.EventMediaRetentionService;
import com.safepath.backend.risk.storage.EventMediaStorage;
import com.safepath.backend.risk.storage.S3EventMediaStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL/HTTP mappings with only the external AWS and Firebase calls replaced. */
@SpringBootTest(properties = {"safepath.media.provider=s3", "safepath.media.s3.bucket=safepath-media-test"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class S3MediaApiDatabaseTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventMediaStorage storage;
    @Autowired EventMediaRetentionService retention;
    @Autowired RiskEventMediaRepository medias;
    @MockitoBean(name = "eventMediaS3Client") S3Client s3;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAdminRoleLookup roles;
    final Map<String, byte[]> objects = new HashMap<>();
    final byte[] bytes = {1, 4, 7, 9};
    long eventId;
    long otherEventId;

    @BeforeEach
    void setup() {
        for (String table : new String[] {"users", "guardian_registrations", "safe_return_sessions", "risk_events", "risk_event_medias"}) {
            jdbc.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING ALL) ON COMMIT DROP");
        }
        long owner = user("owner");
        user("admin");
        user("stranger");
        guardian(owner, "guardian", "ACCEPTED", 1);
        guardian(owner, "no-status", "ACCEPTED", 0);
        guardian(owner, "revoked", "REVOKED", 1);
        var session = UUID.randomUUID();
        jdbc.update("INSERT INTO safe_return_sessions (id,user_id,status,started_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP)",
                session, owner);
        eventId = event(session);
        otherEventId = event(session);
        when(tokens.verifyIdToken(anyString())).thenAnswer(call -> call.getArgument(0));
        when(roles.isAdmin("admin")).thenReturn(true);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            assertThat(request.bucket()).isEqualTo("safepath-media-test");
            try (var stream = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), stream.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> {
            GetObjectRequest request = call.getArgument(0);
            byte[] content = objects.get(request.key());
            if (content == null) throw NoSuchKeyException.builder().statusCode(404).build();
            return new ResponseInputStream<>(GetObjectResponse.builder().contentType("audio/wav")
                    .contentLength((long) content.length).build(), new ByteArrayInputStream(content));
        });
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            objects.remove(call.<DeleteObjectRequest>getArgument(0).key());
            return DeleteObjectResponse.builder().build();
        });
    }

    @Test
    void uploadListDownloadAndDeleteUseS3AndTheV1Metadata() throws Exception {
        assertThat(storage).isInstanceOf(S3EventMediaStorage.class);
        long mediaId = upload();
        var row = jdbc.queryForMap("SELECT event_id,storage_url,file_name,uploaded_at FROM risk_event_medias WHERE id=?", mediaId);
        assertThat(row.get("event_id")).isEqualTo(eventId);
        assertThat(row.get("storage_url").toString()).startsWith("s3://safepath-media-test/risk-events/");
        assertThat(row.get("uploaded_at")).isNotNull();

        mvc.perform(get(path(eventId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(mediaId))
                .andExpect(jsonPath("$[0].fileName").exists())
                // The S3 location is internal and must not reach any client.
                .andExpect(jsonPath("$[0].storageUrl").doesNotExist());
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        mvc.perform(delete(path(eventId) + "/" + mediaId).header("Authorization", "Bearer admin"))
                .andExpect(status().isNoContent());
        assertThat(objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE id=?", Integer.class, mediaId)).isZero();
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadRequiresAViewerAndMatchingEventBeforeReadingS3() throws Exception {
        long mediaId = upload();
        clearInvocations(s3);
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId))).andExpect(status().isUnauthorized());
        for (String uid : new String[] {"stranger", "no-status", "revoked"}) {
            mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path(eventId)).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(path(otherEventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(s3);
    }

    @Test
    void ownerAndStatusGuardianCanListAndDownloadMedia() throws Exception {
        long mediaId = upload();
        for (String uid : new String[] {"owner", "guardian"}) {
            mvc.perform(get(path(eventId)).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(mediaId));
            mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        }
    }

    @Test
    void viewersOtherThanTheOwnerCannotUploadAndDeleteStaysAdminOnly() throws Exception {
        long mediaId = upload();
        clearInvocations(s3);
        for (String uid : new String[] {"guardian", "stranger"}) {
            mvc.perform(multipart(path(eventId)).file(new MockMultipartFile("file", "recording.wav", "audio/wav", bytes))
                            .param("mediaType", "AUDIO").header("Authorization", "Bearer " + uid))
                    .andExpect(status().isForbidden());
        }
        for (String uid : new String[] {"owner", "guardian"}) {
            mvc.perform(delete(path(eventId) + "/" + mediaId).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(s3);
        assertThat(objects).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id=?", Integer.class, eventId)).isOne();
    }

    @Test
    void ownerUploadsManyFilesOfBothTypesKeptInUploadOrder() throws Exception {
        int count = 25;
        for (int i = 1; i <= count; i++) {
            boolean audio = i % 2 == 1;
            String name = String.format(audio ? "chunk_%04d.wav" : "sensor_%04d.json", i);
            mvc.perform(multipart(path(eventId))
                            .file(new MockMultipartFile("file", name, audio ? "audio/wav" : "application/json", bytes))
                            .param("mediaType", audio ? "audio" : "sensor").header("Authorization", "Bearer owner"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.eventId").value(eventId))
                    .andExpect(jsonPath("$.fileName").value(name));
        }
        mvc.perform(get(path(eventId)).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(count))
                .andExpect(jsonPath("$[0].fileName").value("chunk_0001.wav"))
                .andExpect(jsonPath("$[1].mediaType").value("sensor"))
                .andExpect(jsonPath("$[24].fileName").value("chunk_0025.wav"));
        assertThat(objects).hasSize(count);
    }

    @Test
    void ownerStillUploadsAfterTheEventIsDismissed() throws Exception {
        jdbc.update("UPDATE risk_events SET status='DISMISSED' WHERE id=?", eventId);
        mvc.perform(multipart(path(eventId)).file(new MockMultipartFile("file", "chunk_0001.wav", "audio/wav", bytes))
                        .param("mediaType", "audio").header("Authorization", "Bearer owner"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id=?", Integer.class, eventId)).isOne();
    }

    @Test
    void retentionDeletesFilesOlderThanSevenDaysButKeepsTheEventAndMediaRows() throws Exception {
        long oldId = upload();
        jdbc.update("UPDATE risk_event_medias SET uploaded_at = uploaded_at - INTERVAL '8 days' WHERE id=?", oldId);
        mvc.perform(multipart(path(eventId)).file(new MockMultipartFile("file", "fresh.wav", "audio/wav", bytes))
                        .param("mediaType", "AUDIO").header("Authorization", "Bearer admin"))
                .andExpect(status().isCreated());
        long freshId = jdbc.queryForObject("SELECT max(id) FROM risk_event_medias WHERE event_id=?", Long.class, eventId);
        String oldUrl = jdbc.queryForObject("SELECT storage_url FROM risk_event_medias WHERE id=?", String.class, oldId);

        var result = retention.expireBatch();
        medias.flush();

        assertThat(result.cleared()).isOne();
        assertThat(jdbc.queryForObject("SELECT storage_url FROM risk_event_medias WHERE id=?", String.class, oldId)).isNull();
        assertThat(jdbc.queryForObject("SELECT storage_url FROM risk_event_medias WHERE id=?", String.class, freshId)).isNotNull();
        assertThat(objects).hasSize(1).doesNotContainKey(oldUrl.substring("s3://safepath-media-test/".length()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_events WHERE id=?", Integer.class, eventId)).isOne();
        mvc.perform(get(path(eventId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(oldId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(freshId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        assertThat(retention.expireBatch().found()).isZero();
    }

    private long upload() throws Exception {
        mvc.perform(multipart(path(eventId)).file(new MockMultipartFile("file", "recording.wav", "audio/wav", bytes))
                        .param("mediaType", "AUDIO").header("Authorization", "Bearer admin"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.storageUrl").doesNotExist());
        return jdbc.queryForObject("SELECT id FROM risk_event_medias WHERE event_id=?", Long.class, eventId);
    }

    private String path(long id) { return "/api/risk-events/" + id + "/medias"; }
    private long user(String uid) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) VALUES (?,'test',?,'test',?) RETURNING id",
                Long.class, uid, uid, uid + "@example.test");
    }
    private void guardian(long owner, String uid, String status, int viewStatus) {
        jdbc.update("INSERT INTO guardian_registrations (user_id,guardian_id,status,can_view_location) VALUES (?,?,?,?)",
                owner, user(uid), status, viewStatus);
    }
    private long event(UUID session) {
        return jdbc.queryForObject("INSERT INTO risk_events (session_id,event_type,description) VALUES (?,'SOS','media test') RETURNING id",
                Long.class, session);
    }
}
