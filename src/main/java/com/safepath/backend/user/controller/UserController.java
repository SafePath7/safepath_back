package com.safepath.backend.user.controller;

import com.safepath.backend.user.UserService;
import com.safepath.backend.user.FirebaseAccountService;
import com.safepath.backend.user.service.PhoneProtection;

import com.google.firebase.auth.FirebaseAuthException;
import com.safepath.backend.user.dto.PhoneUpdateRequest;
import com.safepath.backend.user.dto.ProfileUpdateRequest;
import com.safepath.backend.user.dto.RegistrationRequest;
import com.safepath.backend.user.dto.RegistrationResponse;
import com.safepath.backend.user.dto.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users/me")
public class UserController {
    // Firebase에서 해당 계정의 Google 이메일과 연결된 전화번호를 확인합니다.
    private final FirebaseAccountService accounts;
    // 전화번호의 중복 확인용 해시를 만들고, 전화번호를 암호화·복호화
    private final PhoneProtection phones;
    private final UserService userService;

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
            UserService.RegistrationResult result = userService.register(uid, name, phones.hash(account.phoneNumber()),
                    phones.encrypt(account.phoneNumber()), account.email());
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(new RegistrationResponse(result.id()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // 내 정보 조회
    @GetMapping
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal String uid) {
        return userService.findProfile(uid, phones).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 이름 수정
    // 이름 앞뒤 공백을 제거한 뒤, 빈 이름이거나 100자를 초과하면 400을 반환
    @PatchMapping
    public ResponseEntity<Void> updateProfile(@AuthenticationPrincipal String uid,
            @RequestBody(required = false) ProfileUpdateRequest request) {
        String name = request == null || request.name() == null ? "" : request.name().strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            return ResponseEntity.badRequest().build();
        }
        if (!userService.updateName(uid, name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    // 전화번호 변경
    @PatchMapping("/phone")
    public ResponseEntity<Void> changePhone(@AuthenticationPrincipal String uid,
            @RequestBody(required = false) PhoneUpdateRequest request) {
        String phone = request == null ? null : request.phoneNumber();
        if (phone == null || !phone.matches("\\+[1-9][0-9]{1,14}")) {
            return ResponseEntity.badRequest().build();
        }
        if (userService.findUserIdByFirebaseUid(uid).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // The existing request cannot prove a fresh, single-use SMS verification.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    // 회원 탈퇴
    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal String uid) {
        try {
            userService.withdraw(uid);  // 실제 삭제
        } catch (UserService.FileDeletionUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.noContent().build();
    }

}
