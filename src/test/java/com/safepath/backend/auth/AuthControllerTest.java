package com.safepath.backend.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    // Firebase와 DB 대신 가짜 객체를 사용합니다.
    private final FirebaseTokenService tokens = mock(FirebaseTokenService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuthController controller = new AuthController(tokens, jdbc);

    @BeforeEach
    void setUp() {
        when(tokens.verifyIdToken("test-token")).thenReturn("firebase-uid");
    }

    @Test
    void returnsExistingUser() {
        // 가입된 사용자는 ID와 REGISTERED를 반환합니다.
        when(jdbc.query(anyString(), any(RowMapper.class), eq("firebase-uid"))).thenReturn(List.of(42L));
        var response = controller.login("Bearer test-token");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new AuthController.LoginResponse(42L, "REGISTERED"), response.getBody());
    }

    @Test
    void returnsSignupRequiredForUnknownUser() {
        // 가입되지 않은 사용자는 SIGNUP_REQUIRED를 반환합니다.
        when(jdbc.query(anyString(), any(RowMapper.class), eq("firebase-uid"))).thenReturn(List.of());
        var response = controller.login("Bearer test-token");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new AuthController.LoginResponse(null, "SIGNUP_REQUIRED"), response.getBody());
    }

    @Test
    void rejectsMissingTokenWithoutQueryingDatabase() {
        // 토큰이 없으면 DB 조회 없이 401을 반환합니다.
        assertEquals(HttpStatus.UNAUTHORIZED, controller.login(null).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidTokenWithoutQueryingDatabase() {
        // 유효하지 않은 토큰도 DB 조회 없이 401을 반환합니다.
        when(tokens.verifyIdToken("invalid")).thenThrow(new BadCredentialsException("invalid"));
        assertEquals(HttpStatus.UNAUTHORIZED, controller.login("Bearer invalid").getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
