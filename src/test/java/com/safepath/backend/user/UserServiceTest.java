package com.safepath.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class UserServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserService users = new UserService(jdbc);

    @Test
    void returnsMappedLongForExistingUid() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(42L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("firebase-uid")))
                .thenAnswer(invocation -> {
                    RowMapper<Long> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        assertEquals(Optional.of(42L), users.findUserIdByFirebaseUid("firebase-uid"));
        verify(jdbc).query(eq("SELECT id FROM users WHERE firebase_uid = ?"),
                any(RowMapper.class), eq("firebase-uid"));
    }

    @Test
    void returnsEmptyForUnknownUid() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("unknown"))).thenReturn(List.of());

        assertTrue(users.findUserIdByFirebaseUid("unknown").isEmpty());
    }
}
