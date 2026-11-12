package com.safepath.backend.user;

import com.google.firebase.auth.FirebaseAuthException;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {
    // users 테이블을 조회하고 데이터를 저장합니다.
    private final JdbcTemplate jdbc;
    // Firebase에서 해당 계정의 Google 이메일과 연결된 전화번호를 확인합니다.
    private final FirebaseAccountService accounts;
    // 전화번호의 중복 확인용 해시를 만들고, 전화번호를 암호화·복호화
    private final PhoneProtection phones;
    private final UserService userService;

    public UserController(JdbcTemplate jdbc, FirebaseAccountService accounts, PhoneProtection phones, UserService userService) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.phones = phones;
        this.userService = userService;
    }

    // 최초 가입
    @PostMapping("/registration")
    public ResponseEntity<RegistrationResponse> register(@AuthenticationPrincipal String uid,
            @RequestBody(required = false) RegistrationRequest request) throws FirebaseAuthException {
        // 이미 가입했는지 확인
        Long existing = userService.findUserIdByFirebaseUid(uid).orElse(null);
        if (existing != null) return ResponseEntity.ok(new RegistrationResponse(existing));

        // Firebase 계정 정보 확인
        FirebaseAccountService.Account account = accounts.getAccount(uid);
        if (account == null) return ResponseEntity.unprocessableEntity().build();
        // 이름 결정
        String name = request == null ? null : request.name();
        if (name == null || name.isBlank()) name = account.displayName();
        if (name == null || name.isBlank() || name.length() > 100) return ResponseEntity.badRequest().build();

        try {
            // 회원정보 저장
            List<Long> inserted = jdbc.query("""
                    INSERT INTO users (firebase_uid, name, phone_hash, phone_encrypted, email)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (firebase_uid) DO NOTHING RETURNING id
                    """, (rs, rowNum) -> rs.getLong("id"), uid, name, phones.hash(account.phoneNumber()),
                    phones.encrypt(account.phoneNumber()), account.email());
            if (!inserted.isEmpty()) return ResponseEntity.status(HttpStatus.CREATED).body(new RegistrationResponse(inserted.getFirst()));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Long id = userService.findUserIdByFirebaseUid(uid).orElse(null);
        if (id == null) throw new IllegalStateException("Registration conflict without existing user");
        return ResponseEntity.ok(new RegistrationResponse(id));
    }

    // 내 정보 조회
    @GetMapping
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal String uid) {
        // 인증 정보에 저장된 UID로 users 테이블을 조회
        // phones.decrypt()로 전화번호 복호화
        return jdbc.query("SELECT id, name, email, phone_encrypted FROM users WHERE firebase_uid = ?",
                (rs, rowNum) -> new UserResponse(rs.getLong("id"), rs.getString("name"),
                        rs.getString("email"), phones.decrypt(rs.getString("phone_encrypted"))), uid)
                .stream().findFirst().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // API에서 주고받는 데이터의 모양을 정의
    public record RegistrationRequest(String name) {}   // 가입 요청에서 입력받는 이름
    public record RegistrationResponse(Long userId) {}  // 가입 결과로 반환하는 사용자 ID
    public record UserResponse(Long userId, String name, String email, String phoneNumber) {}   //내 정보 조회 결과
}
