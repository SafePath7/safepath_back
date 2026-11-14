package com.safepath.backend.risk;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.guardianrisk.FirebaseAdminRoleLookup;

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
        for (String table : new String[] {"users", "safe_return_sessions", "risk_events", "risk_event_medias"}) {
            jdbc.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING ALL) ON COMMIT DROP");
        }
        long owner = user("owner");
        user("admin");
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
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(mediaId));
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
    void downloadRequiresAdminAndMatchingEventBeforeReadingS3() throws Exception {
        long mediaId = upload();
        clearInvocations(s3);
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(eventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path(otherEventId)).param("mediaId", Long.toString(mediaId)).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(s3);
    }

    private long upload() throws Exception {
        mvc.perform(multipart(path(eventId)).file(new MockMultipartFile("file", "recording.wav", "audio/wav", bytes))
                        .param("mediaType", "AUDIO").header("Authorization", "Bearer admin"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM risk_event_medias WHERE event_id=?", Long.class, eventId);
    }

    private String path(long id) { return "/api/risk-events/" + id + "/medias"; }
    private long user(String uid) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) VALUES (?,'test',?,'test',?) RETURNING id",
                Long.class, uid, uid, uid + "@example.test");
    }
    private long event(UUID session) {
        return jdbc.queryForObject("INSERT INTO risk_events (session_id,event_type,description) VALUES (?,'SOS','media test') RETURNING id",
                Long.class, session);
    }
}
