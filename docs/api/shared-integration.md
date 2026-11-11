# API 공통 연결 사항

이 변경은 기존 `develop`의 보안 설정, Firebase 초기화·검증, 로그인·알림·WebSocket 코드를 그대로 사용합니다.

## 현재 사용자 조회

`security.CurrentUser`는 인증된 `Authentication`에서 다음 값을 읽습니다.

- `authentication.getName()`: Firebase UID. `users.firebase_uid`로 DB 회원 ID를 찾습니다.
- `authentication.getAuthorities()`: `ROLE_ADMIN`이 있을 때만 관리자로 판단합니다.
- 연결된 DB 회원이 없으면 404, 업무 권한이 부족하면 403입니다.

별도 `SecurityFilterChain`, Firebase 초기화 코드, 토큰 검증기 또는 인증 필터를 추가하지 않습니다.

## 인증 담당자에게 필요한 연결

현재 develop의 `auth.FirebaseAuthenticationFilter`는 검증된 UID를 전달하지만 권한 목록을 비워 둡니다. 따라서 일반 회원의 소유자 기능은 연결 가능하며, 관리자 전용 미디어 API와 보호자 중지 기능은 `ROLE_ADMIN` 전달이 구현되기 전에는 403으로 거절됩니다.

관리자 판정과 검증된 권한 전달은 인증 담당자가 구현합니다. 이 브랜치에서는 보안 설정을 변경하거나 권한을 임의로 부여하지 않습니다.

## DB와 공통 오류

기존 V1 테이블을 사용합니다. V1은 수정하지 않고 새 마이그레이션을 추가하지 않습니다. local 프로필의 Flyway 자동 실행을 비활성화하여 기존 스키마를 사용하도록 합니다. develop의 `.env` 로딩과 Firebase 파일 경로 설정은 유지합니다.

`common.ApiException`과 `ApiExceptionHandler`가 업무 오류를 HTTP 상태 코드와 ProblemDetail로 반환합니다.

## 브랜치 분리

- `feature/guardian-registration-v1`: 보호자 API.
- `feature/risk-event-media-s3`: 위험 이벤트·S3 미디어 API.

두 브랜치는 동일한 공통 커밋을 포함하므로 어느 쪽부터 병합해도 공통 파일을 중복 구현하지 않습니다.
