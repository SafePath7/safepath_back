# 안전귀가 활성 세션 저장소

## 목적 및 범위

`ActiveSessionStore`는 일반적인 활성 안전귀가 세션을 애플리케이션 프로세스의 메모리에 보관합니다. 저장소 자체는 데이터베이스를 사용하거나 HTTP 엔드포인트를 구현하지 않습니다.

메모리 기반 모델은 JPA 엔티티와 별개입니다. `UUID` 타입의 세션 ID, `Long` 타입의 사용자 ID, `Instant` 타입의 시작 시각을 포함합니다. 현재 확인된 API 명세에 세션 상태값이 확정되어 있지 않아 별도의 `status` 필드를 정의하지 않았습니다.

## 프로젝트 구조

- `src/main/java/com/safepath/backend/session/model/ActiveSession.java` — 변경 불가능한 메모리 기반 세션 모델
- `src/main/java/com/safepath/backend/session/store/ActiveSessionStore.java` — 메모리 저장 및 조회 기능을 제공하는 Spring 컴포넌트
- `src/main/java/com/safepath/backend/session/service/SafeReturnSessionService.java` — 세션 시작, 활성 세션 조회, 종료 비즈니스 로직
- `src/main/java/com/safepath/backend/session/controller/SafeReturnSessionController.java` — REST API 요청 처리
- `src/main/java/com/safepath/backend/session/exception/SafeReturnSessionExceptionHandler.java` — 세션 Controller 범위 예외 처리
- `src/main/java/com/safepath/backend/session/dto/ActiveSessionResponse.java` — 활성 세션 응답 DTO
- `src/main/java/com/safepath/backend/session/dto/SessionApiErrorResponse.java` — 오류 응답 DTO
- `src/test/java/com/safepath/backend/session/store/ActiveSessionStoreTest.java` — 저장소 단위 테스트
- `src/test/java/com/safepath/backend/session/service/SafeReturnSessionServiceTest.java` — 세션 서비스 단위 테스트

## 세션 시작

`ActiveSession startSession(Long userId)`는 사용자 ID가 `null`이거나 0 이하이면 `IllegalArgumentException`을 발생시킵니다. 유효한 ID이면 UUID와 UTC 기준 `Instant` 시작 시각을 생성하고, 새로운 `ActiveSession`을 `ActiveSessionStore`에 저장한 뒤 반환합니다.

동일 사용자에게 이미 활성 세션이 있다면 저장소가 `IllegalStateException`을 발생시킵니다. 서비스는 별도로 조회한 뒤 저장하는 방식 대신 저장소의 원자적 중복 검사를 이용합니다.

서비스 자체는 사용자 ID의 형식만 검사합니다. 호출자를 인증하거나 실제 사용자 존재 여부를 확인하지 않으며, 해당 기능은 추후 승인된 인증 및 사용자 서비스 연동이 필요합니다.

## REST API

현재 인증은 Postman 및 로컬 검증에만 쓰는 임시 HTTP Basic 테스트 인증입니다. `local`과 `local-test-auth` 프로필이 모두 활성화된 경우에만 사용할 수 있으며, 정식 사용자 인증으로 사용하면 안 됩니다. 테스트 모드에서는 인증된 `LocalTestUserPrincipal`에서 `Long userId`를 가져옵니다. 요청 본문, 쿼리 파라미터, 임의의 헤더에 담긴 사용자 ID는 신원 확인에 사용하지 않습니다. Firebase 인증 및 UID 매핑은 아직 연결되지 않았습니다.

| API 이름 | HTTP Method 및 경로 | Request JSON | 성공 Response JSON |
|---|---|---|---|
| 세션 시작 | `POST /api/safe-return/sessions` | 없음 | `201 Created`, 아래 세션 JSON |
| 세션 상태 조회 | `GET /api/safe-return/sessions/{sessionId}/status` (`sessionId`: UUID) | 없음 | `200 OK`, 아래 세션 JSON |
| 세션 종료 | `PATCH /api/safe-return/sessions/{sessionId}/end` (`sessionId`: UUID) | 없음 | `204 No Content`, body 없음 |

세션 시작 및 조회 응답의 필드와 Java 자료형은 `id: UUID`, `startedAt: Instant`입니다. JSON에서는 각각 문자열로 직렬화됩니다. `userId` 및 `status` 필드는 포함하지 않습니다.

```json
{
  "id": "6f596d61-5f29-4e56-92e6-1672dc6476b9",
  "startedAt": "2026-11-08T10:00:00Z"
}
```

`ActiveSession` 모델에 상태값이 없으므로 `status` 필드는 반환하지 않습니다.

현재 `session.exception.SafeReturnSessionExceptionHandler`는 `@RestControllerAdvice(assignableTypes = SafeReturnSessionController.class)`로 세션 Controller에만 적용됩니다. 오류 JSON은 `{ "code": String, "message": String }` 형식입니다.

```json
{
  "code": "SESSION_NOT_FOUND",
  "message": "The active session was not found."
}
```

| HTTP 상태 코드 | 오류 코드 | 발생 상황 |
|---|---|---|
| `400 Bad Request` | `INVALID_SESSION_ID` 또는 `INVALID_REQUEST` | UUID 형식 오류 또는 잘못된 요청 |
| `401 Unauthorized` | `UNAUTHENTICATED` | 인증 누락 또는 지원되지 않는 인증 |
| `404 Not Found` | `SESSION_NOT_FOUND` | 세션 없음, 이미 종료됨, 또는 소유자가 다름 |
| `409 Conflict` | `ACTIVE_SESSION_CONFLICT` | 동일 사용자의 활성 세션 중복 시작 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류(내부 예외 상세 내용은 노출하지 않음) |

소유권 불일치에 대한 `404` 응답은 현재 구현 정책이며, 최종 상태 코드 및 오류 정책에 대한 팀 합의가 필요합니다.

### Postman 요청 예시

아래 Basic Auth 예시는 `local,local-test-auth` 프로필의 임시 로컬 테스트에만 해당합니다. 운영 및 Dev 인증 방식이 아니며, 정식 Firebase 인증은 아직 구현되지 않았습니다. 요청에 사용자 ID를 별도로 넣지 않습니다.

```http
POST http://127.0.0.1:8080/api/safe-return/sessions
Authorization: Basic <로컬 테스트 인증 정보>
```

```http
GET http://127.0.0.1:8080/api/safe-return/sessions/{반환된-session-id}/status
Authorization: Basic <로컬 테스트 인증 정보>
```

```http
PATCH http://127.0.0.1:8080/api/safe-return/sessions/{반환된-session-id}/end
Authorization: Basic <로컬 테스트 인증 정보>
```

요청 본문은 필요하지 않습니다. 로컬 환경에서 Postman을 이용한 세션 시작·조회·종료, 인증 및 소유권 검증, 중복 시작 차단, 오류 응답 수동 테스트를 진행했습니다. 각 요청의 실제 결과는 저장한 캡처로 확인할 수 있습니다.

## 주요 메서드

- `void saveSession(ActiveSession session)` — 세션 저장. 동일 UUID 또는 동일 사용자의 활성 세션이 존재하면 `IllegalStateException` 발생
- `Optional<ActiveSession> findById(UUID sessionId)` — UUID로 세션 조회
- `Optional<ActiveSession> findByUserId(Long userId)` — 사용자의 활성 세션 조회
- `void remove(UUID sessionId)` — 세션 제거. 존재하지 않는 ID를 제거해도 영향 없음
- `void endSession(UUID sessionId, Long userId)` — 소유자가 일치할 때만 활성 세션 제거. 입력값이 유효하지 않으면 `IllegalArgumentException`, 세션이 없거나 이미 종료되었으면 `NoSuchElementException`, 다른 사용자의 세션이면 `SecurityException` 발생
- `ActiveSession getSessionStatus(UUID sessionId, Long userId)` — 소유권을 검사한 후 활성 세션 반환
- `removeIfOwned(UUID, Long)` — 저장소에서 존재 여부 및 소유권을 확인하고 두 인덱스에서 세션 제거
- `SafeReturnSessionController` — 세 가지 REST 엔드포인트를 제공하며, 현재 임시 로컬 테스트 인증의 `LocalTestUserPrincipal`에서 호출자 ID 획득

사용 예시:

```java
ActiveSession session = safeReturnSessionService.startSession(42L);
Optional<ActiveSession> found = activeSessionStore.findById(session.id());
safeReturnSessionService.endSession(session.id(), 42L);
```

## 데이터 일관성 및 제한 사항

저장소는 세션 UUID와 사용자 ID를 각각 키로 하는 동시성 해시 맵을 사용합니다. 공개 메서드에서 접근을 동기화하여 두 인덱스를 원자적으로 갱신하고, 동일 사용자의 동시 세션 생성 요청이 기존 값을 조용히 덮어쓰지 못하도록 합니다. 사용자당 활성 세션은 하나만 허용됩니다.

세션 종료 시 동기화된 `removeIfOwned(UUID, Long)` 작업으로 세션 존재 여부와 소유권을 검사한 뒤, 하나의 잠금 구간에서 두 인덱스의 값을 제거합니다. 다른 사용자는 세션을 삭제할 수 없습니다. 동일 세션에 종료 요청이 동시에 발생하면 하나만 성공하며, 이후 요청은 이미 종료된 세션과 동일한 조회 실패 오류를 받습니다. 종료 후에는 해당 사용자가 새 활성 세션을 시작할 수 있습니다.

메모리 데이터는 애플리케이션 프로세스 재시작 시 사라지고, 여러 서버 인스턴스 간에 공유되지 않습니다. 위험 확정 상황의 영구 저장 및 다중 인스턴스 간 조정은 별도 합의된 연동 작업이 필요합니다.

## 데이터베이스 및 설정

일반 활성 세션의 시작·조회·종료는 DB 테이블, 엔티티, Repository, 마이그레이션 또는 환경변수에 접근하지 않습니다. 일반 세션 종료는 메모리에서만 제거되며 세션 이력 레코드를 저장하지 않습니다. 이 기능을 위해 DB 스키마를 변경할 필요는 없습니다.

## 로컬 Postman 테스트 인증

임시 HTTP Basic 테스트 계정은 `local`과 `local-test-auth` 프로필이 모두 활성화된 경우에만 사용할 수 있습니다. `local-test-auth`는 기존 VS Code 실행 설정과 별도로 구분됩니다.

`LocalTestAuthStartupGuard`는 다른 프로필이 함께 활성화되었거나, 데이터 소스가 명시적인 루프백 PostgreSQL JDBC URL이 아니거나, 데이터 소스 재정의가 설정되었거나, 테스트 비밀번호가 누락되었거나, 서버 바인딩 주소가 정확히 `127.0.0.1`이 아니면 테스트 프로필 사용을 거부합니다.

| 테스트 계정 | Principal `userId` |
|---|---:|
| `postman-user-1` | `9000000001` |
| `postman-user-2` | `9000000002` |

다음 운영체제 환경변수를 로컬에 설정합니다. **비밀번호 값은 소스 코드, `.env`, Postman 내보내기 파일, 로그에 기록하지 않습니다.**

- `SAFEPATH_LOCAL_TEST_USER_1_PASSWORD`
- `SAFEPATH_LOCAL_TEST_USER_2_PASSWORD`

Windows PowerShell에서는 비밀번호를 화면에 표시하지 않는 입력창으로 받아 두 프로필을 활성화한 상태에서 실행합니다. 로컬 PostgreSQL 연결에 필요한 일반 환경변수도 별도로 제공해야 합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local,local-test-auth'
$secure = Read-Host 'Local test user 1 password' -AsSecureString
$env:SAFEPATH_LOCAL_TEST_USER_1_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
$secure = Read-Host 'Local test user 2 password' -AsSecureString
$env:SAFEPATH_LOCAL_TEST_USER_2_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
.\gradlew.bat bootRun
```

Postman의 Authorization에서 **Basic Auth**를 선택하고 위의 사용자명과 해당 비밀번호를 입력합니다. 비밀번호는 내보내지 않는 로컬 변수로 관리합니다. `userId`를 본문, 쿼리 문자열, 헤더에 직접 전달하지 않습니다. 테스트 모드에서 사용자 ID의 유일한 출처는 인증된 `LocalTestUserPrincipal`입니다.

REST Controller는 기존 메모리 기반 Service 및 Store와 연결되어 있습니다. `POST /api/safe-return/sessions`로 시작한 뒤 반환된 UUID로 GET 조회와 PATCH 종료를 요청합니다.

로컬 테스트 인증은 Firebase 인증이 아니며 Firebase 토큰 검증이나 Firebase UID와 DB 사용자 ID의 매핑을 수행하지 않습니다. 위 사용자 ID는 메모리 세션 흐름을 위한 가상 ID입니다. HTTP Basic은 인증 정보를 자체 암호화하지 않으므로 IPv4 루프백에만 바인딩하며 프록시, 터널 또는 외부 네트워크 인터페이스로 노출해서는 안 됩니다. CSRF는 이 상태 비저장 로컬 테스트용 보안 체인에서만 비활성화됩니다.

보호 장치는 데이터 소스 등 애플리케이션 싱글턴이 초기화되기 전에 실행됩니다. `local` 프로필만 활성화하면 테스트 계정 및 테스트 보안 체인이 등록되지 않습니다. `local-test-auth`와 `dev` 또는 다른 비로컬 프로필을 함께 사용하는 구성은 거부됩니다.

## 테스트 실행 방법

Windows에서 세션 서비스 및 저장소 단위 테스트 실행:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"
```

로컬 인증 보호 장치 및 HTTP Basic 테스트 실행:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.security.local.*"
```

보안 및 REST Controller HTTP 테스트는 메모리 기반 가상 비밀번호를 사용하므로 PostgreSQL 연결이나 로컬 테스트 비밀번호 환경변수가 필요하지 않습니다.

단위 및 통합 테스트에서는 세션 저장과 두 ID 기준 조회, 미존재 세션 조회, 제거, 중복 UUID·사용자 차단, 모델의 null 검증, 세션 시작과 저장, UUID·시작 시각 생성, 잘못된 사용자 ID, 동시 세션 시작, 소유자 조회, 삭제된 세션, 잘못된 인자, 타 사용자 접근 거부를 검증합니다.

종료 테스트에서는 소유자만 제거 가능한지, 두 인덱스에서 제거되는지, 종료 후 재시작이 가능한지, 존재하지 않거나 이미 종료된 세션 처리, 잘못된 입력, 동시 종료 요청 등을 확인합니다.

### 이전 기본 테스트 기록

- 실행 명령어: `.\gradlew.bat test`
- 기록된 결과: **총 25개 테스트 중 25개 통과, 실패 0개, 오류 0개, 건너뜀 0개**
- `SafeReturnSessionServiceTest`: 15개 통과
- `ActiveSessionStoreTest`: 9개 통과
- `BackendApplicationTests.contextLoads()`: 1개 통과
- `local` 프로필로 Spring Boot를 시작하여 Docker PostgreSQL에 정상 연결
- Flyway V1 마이그레이션 정상 적용
- 이전 `contextLoads()` 실패는 기본 프로필에 DataSource URL이 없어서 발생했으며, `local` 프로필 및 DB 환경변수를 설정한 후 통과

### REST Controller 검증 기록

- 선택 테스트 명령어: `.\gradlew.bat test --tests "com.safepath.backend.security.local.*" --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"`
- 기록된 결과: **50개 통과, 실패 0개**. REST Controller HTTP 통합 테스트 12개, 로컬 인증·보안 테스트 14개, 기존 Service/Store 테스트 24개 포함
- 전체 테스트 명령어: `.\gradlew.bat test`
- 기록된 결과: **총 51개 중 50개 통과, 1개 실패**. 기본 프로필 테스트 프로세스에 DataSource URL 또는 환경변수가 없어 `BackendApplicationTests.contextLoads()`에서 데이터 소스를 구성하지 못함. AWS RDS 연결 시도는 없었음

위 결과는 기존 문서에 기록된 테스트 결과이며, 이번 README 수정 과정에서 전체 테스트를 새로 실행한 결과는 아닙니다.

### Postman 수동 검증

로컬 테스트 인증 계정 두 개를 이용하여 세션 시작, 조회, 종료 및 오류 응답을 순서대로 확인했습니다. 테스트 항목에는 정상 시작(`201`), 정상 조회(`200`), 정상 종료(`204`), 종료 후 조회(`404`), 세션 재시작(`201`), 타 사용자 조회 차단(`404`), 중복 시작 차단(`409`), 미인증 조회 차단(`401`), 잘못된 UUID(`400`), 미존재 세션(`404`), 타 사용자 종료 차단(`404`), 무단 종료 시도 후 기존 세션 유지(`200`) 등이 포함됩니다.

테스트별 요청과 응답은 별도로 저장한 Postman 캡처를 기준으로 확인합니다. 이번 문서 편집 과정에서는 Postman 요청을 다시 실행하지 않았습니다.

### 최근 컴파일 확인

- 실행 명령어: `.\gradlew.bat compileJava` 및 세션 Controller, Service, Store 선택 테스트
- 결과: **컴파일 성공; 선택 테스트 36개 통과, 실패·오류 0개** (2026-11-10)
- Exception Handler를 `session.exception`으로 이동하고 테스트 import를 수정한 후 검증

## 현재 구현 상태

- 메모리 기반 세션 저장소와 `SafeReturnSessionService` 구현 완료
- `SafeReturnSessionController`에서 세 가지 REST API를 기존 Service에 연결하고, 인증된 로컬 테스트 Principal에서 사용자 ID 획득
- `session.exception.SafeReturnSessionExceptionHandler`가 세션 Controller에만 적용되며 현재 오류 코드 및 JSON 응답을 처리
- 소유권 불일치의 `404` 정책 최종 확인은 팀 합의 필요
- Firebase 인증 및 Firebase UID를 내부 `users.id` (`Long`)로 매핑하는 기능은 DB 담당 팀과의 연동 예정이며 현재 미구현
- Draft PR #2는 팀 리뷰 및 병합 대기 중

## 추후 결정 사항

- 세션 상태 및 추가 필드는 관련 명세가 승인된 후 반영
- 메모리 저장소와 향후 세션 서비스 및 위험 확정 처리 기능 간의 연동 방식 협의
