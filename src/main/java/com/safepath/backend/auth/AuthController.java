package com.safepath.backend.auth;

import com.safepath.backend.user.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 로그인 api
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final FirebaseTokenService tokenService;
    private final UserService userService;

    public AuthController(FirebaseTokenService tokenService, UserService userService) {
        this.tokenService = tokenService;
        this.userService = userService;
    }

    // Authorization: Bearer Firebase_ID_토큰
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        // Bearer 토큰이 없으면 인증을 거부합니다.
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String uid;
        try {
            // Firebase ID 토큰을 검증하고 사용자 UID를 가져옵니다.
            uid = tokenService.verifyIdToken(authorization.substring(7).trim());
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // UID로 가입된 사용자를 찾아 가입 여부를 반환합니다.
        // SIGNUP_REQUIRED : 토큰 검증 성공. 해당 UID가 로컬 DB에 아직 없음  (DB에 없음 -> 신규 사용자)
        // REGISTERED : 가입한 사용자가 같은 계정으로 재로그인
        LoginResponse response = userService.findUserIdByFirebaseUid(uid)
                .map(id -> new LoginResponse(id, "REGISTERED"))
                .orElseGet(() -> new LoginResponse(null, "SIGNUP_REQUIRED"));
        return ResponseEntity.ok(response);
    }

    public record LoginResponse(Long userId, String status) {}
}
