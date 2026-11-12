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

서비스 자체는 사용자 ID의 형식만 검사합니다. Controller는 Firebase 인증 필터가 검증한 UID를 `UserService`로 조회한 뒤 DB의 `Long userId`를 전달합니다.

## REST API

모든 세션 요청은 `Authorization: Bearer <Firebase ID token>`을 사용합니다. Firebase 인증 필터가 토큰을 검증하고 principal에 UID 문자열을 설정합니다. Controller는 `UserService.findUserIdByFirebaseUid(String)`으로 가입된 사용자의 DB `Long userId`를 조회합니다. 인증되지 않은 요청은 `401`, Firebase 인증은 됐지만 가입되지 않은 UID는 `403 USER_NOT_REGISTERED`를 받습니다. 사용자 ID는 요청 본문, 쿼리 또는 임의 헤더에서 가져오지 않습니다.

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
| `403 Forbidden` | `USER_NOT_REGISTERED` | 유효한 Firebase UID에 대응하는 가입 사용자가 없음 |
| `404 Not Found` | `SESSION_NOT_FOUND` | 세션 없음, 이미 종료됨, 또는 소유자가 다름 |
| `409 Conflict` | `ACTIVE_SESSION_CONFLICT` | 동일 사용자의 활성 세션 중복 시작 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류(내부 예외 상세 내용은 노출하지 않음) |

소유권 불일치에 대한 `404` 응답은 현재 구현 정책으로, 다른 사용자의 세션 존재 여부를 노출하지 않습니다.

### Postman 요청 예시

요청에는 Firebase ID token을 사용하며 사용자 ID를 별도로 전달하지 않습니다.

```http
POST http://127.0.0.1:8080/api/safe-return/sessions
Authorization: Bearer <Firebase ID token>
```

```http
GET http://127.0.0.1:8080/api/safe-return/sessions/{반환된-session-id}/status
Authorization: Bearer <Firebase ID token>
```

```http
PATCH http://127.0.0.1:8080/api/safe-return/sessions/{반환된-session-id}/end
Authorization: Bearer <Firebase ID token>
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
- `SafeReturnSessionController` — 세 가지 REST 엔드포인트를 제공하며, Firebase UID를 공용 `UserService`로 DB 사용자 ID에 매핑

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

## 테스트 실행 방법

Windows에서 세션 서비스 및 저장소 단위 테스트 실행:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"
```

Firebase 인증 필터를 통한 세션 Controller 테스트 실행:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.controller.SafeReturnSessionControllerTest"
```

Controller 테스트는 Firebase 토큰 검증 결과와 사용자 DB 조회를 mock으로 대체하지만, 실제 `FirebaseAuthenticationFilter`와 공통 `SecurityConfig` 체인을 사용합니다. Firebase 인증 정보나 PostgreSQL 연결은 필요하지 않습니다.

단위 및 통합 테스트에서는 세션 저장과 두 ID 기준 조회, 미존재 세션 조회, 제거, 중복 UUID·사용자 차단, 모델의 null 검증, 세션 시작과 저장, UUID·시작 시각 생성, 잘못된 사용자 ID, 동시 세션 시작, 소유자 조회, 삭제된 세션, 잘못된 인자, 타 사용자 접근 거부를 검증합니다.

종료 테스트에서는 소유자만 제거 가능한지, 두 인덱스에서 제거되는지, 종료 후 재시작이 가능한지, 존재하지 않거나 이미 종료된 세션 처리, 잘못된 입력, 동시 종료 요청 등을 확인합니다.

### Firebase 인증 연동 전의 테스트 기록 (참고용)

다음 결과는 임시 로컬 인증을 사용하던 이전 코드의 기록이며 현재 인증 경로의 검증 결과가 아닙니다.
- 실행 명령어: `.\gradlew.bat test`
- 기록된 결과: **총 25개 테스트 중 25개 통과, 실패 0개, 오류 0개, 건너뜀 0개**
- `SafeReturnSessionServiceTest`: 15개 통과
- `ActiveSessionStoreTest`: 9개 통과
- `BackendApplicationTests.contextLoads()`: 1개 통과
- `local` 프로필로 Spring Boot를 시작하여 Docker PostgreSQL에 정상 연결
- Flyway V1 마이그레이션 정상 적용
- 이전 `contextLoads()` 실패는 기본 프로필에 DataSource URL이 없어서 발생했으며, `local` 프로필 및 DB 환경변수를 설정한 후 통과

## 현재 구현 상태

- 메모리 기반 세션 저장소와 `SafeReturnSessionService` 구현 완료
- `SafeReturnSessionController`에서 세 가지 REST API를 기존 Service에 연결하고, 검증된 Firebase UID를 공용 `UserService`로 DB 사용자 ID에 매핑
- `session.exception.SafeReturnSessionExceptionHandler`가 세션 Controller에만 적용되며 현재 오류 코드 및 JSON 응답을 처리
- 미가입 Firebase UID는 `403 USER_NOT_REGISTERED`로 거부
- 소유권 불일치 및 세션 미존재는 `404 SESSION_NOT_FOUND`로 처리
- 기존 메모리 저장소 기능은 프로세스 재시작 시 유지되지 않으며 DB 영속화는 별도 범위

## 추후 결정 사항

- 세션 상태 및 추가 필드는 관련 명세가 승인된 후 반영
- 메모리 저장소와 향후 세션 서비스 및 위험 확정 처리 기능 간의 연동 방식 협의
