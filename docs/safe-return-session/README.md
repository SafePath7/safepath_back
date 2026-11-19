# 안전귀가 활성 세션 저장소

## 선택 경로 연동

경로 추천 `POST /api/routes` 응답에서 선택한 경로를 세션 시작 요청의 `selectedRoute`로 전달할 수 있습니다. 기존 `guardianIds` 검증은 그대로이며, 기존 클라이언트와의 호환을 위해 `selectedRoute`는 선택 사항입니다.

```json
{
  "guardianIds": [2, 3],
  "selectedRoute": {
    "type": "SAFEST",
    "coordinates": [
      { "latitude": 35.8564, "longitude": 128.4938 },
      { "latitude": 35.865, "longitude": 128.505 }
    ],
    "distanceMeters": 1800,
    "durationSeconds": 1200,
    "safetyScore": 65,
    "destinationName": "CU 대구신당점"
  }
}
```

소유자의 `GET /api/safe-return/sessions/{sessionId}/status`는 `selectedRoute`를 함께 반환합니다. 선택된 보호자는 `GET /api/guardian/monitoring/sessions/{sessionId}/route`로 조회하며, 기존 인증과 수락 관계, 선택 보호자 여부 및 현재 LOCATION 권한을 검사합니다. 경로가 없는 세션은 보호자 경로 조회에서 204를 반환합니다. 상태 조회에는 경로를 포함하지 않습니다.

`destinationName`은 프런트가 선택한 목적지 이름이며 선택 사항이고 최대 200자입니다. 생략하면 `null`입니다. 목적지 이름을 포함한 경로는 프런트가 보낸 값이며 서버가 추천 결과와 일치하는지 검증하지 않습니다. 좌표 범위, 좌표 수, 유형 및 거리·시간·점수 범위는 검증합니다. 세션과 같이 메모리에만 유지되므로 종료 또는 서버 재시작 시 사라지고 서버 인스턴스 간 공유되지 않습니다. 경로의 중간 변경은 지원하지 않습니다.

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
| 세션 시작 | `POST /api/safe-return/sessions` | `{"guardianIds":[2,3]}` | `201 Created`, `{id, startedAt, channelId}` |
| 세션 상태 조회 | `GET /api/safe-return/sessions/{sessionId}/status` (`sessionId`: UUID) | 없음 | `200 OK`, 아래 세션 JSON |
| 세션 종료 | `PATCH /api/safe-return/sessions/{sessionId}/end` (`sessionId`: UUID) | 없음 | `204 No Content`, body 없음 |

세션 상태 조회 응답의 필드와 Java 자료형은 `id: UUID`, `startedAt: Instant`입니다. 시작 응답에는 실제 생성된 채널의 `channelId: String`도 포함됩니다. `userId` 및 `status` 필드는 포함하지 않습니다. 시작은 `StartSessionRequest`/`StartSessionResponse`, 상태 조회는 기존 `ActiveSessionResponse`를 사용합니다.

시작 요청의 `guardianIds`는 최소 1명 이상이어야 하며, 전체 보호자가 수락 관계와 LOCATION 권한을 통과해야 합니다. 본인·유효하지 않은 ID를 거부하고, 중복 ID는 기존 Service 정책대로 하나로 합칩니다. 인증된 사용자의 ID만 사용합니다. Body 없는 구형 클라이언트는 이제 `400 INVALID_REQUEST`를 받으므로 프론트 업데이트가 필요합니다. 기존 내부 `startSession(Long)`은 보존하지만 REST에서는 사용하지 않습니다.

시작 성공 응답 예시(실제 값은 요청마다 생성):

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "startedAt": "2026-11-15T09:00:00Z",
  "channelId": "generated-channel-id"
}
```

```json
{
  "id": "6f596d61-5f29-4e56-92e6-1672dc6476b9",
  "startedAt": "2026-11-08T10:00:00Z"
}
```

`ActiveSession` 모델에 상태값이 없으므로 `status` 필드는 반환하지 않습니다.

현재 `session.exception.SafeReturnSessionExceptionHandler`는 세션 Controller와 GuardianMonitoringController에 적용됩니다. 오류 JSON은 `{ "code": String, "message": String }` 형식입니다. 보안 필터가 Controller 진입 전에 거부한 요청은 기존 설정대로 상태 코드만 반환할 수 있습니다.

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
| `403 Forbidden` | `USER_NOT_REGISTERED` | 미가입 또는 보호자 선택·모니터링 권한 거부. 기존 AccessDeniedException 매핑 유지로 코드·메시지가 권한 거부 사유를 세분하지 않음 |
| `404 Not Found` | `SESSION_NOT_FOUND` | 세션 없음, 이미 종료됨, 또는 소유자가 다름 |
| `409 Conflict` | `ACTIVE_SESSION_CONFLICT` | 동일 사용자의 활성 세션 중복 시작 |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류(내부 예외 상세 내용은 노출하지 않음) |

소유권 불일치에 대한 `404` 응답은 현재 구현 정책으로, 다른 사용자의 세션 존재 여부를 노출하지 않습니다.

### Postman 요청 예시

요청에는 Firebase ID token을 사용하며 사용자 ID를 별도로 전달하지 않습니다.

```http
POST http://127.0.0.1:8080/api/safe-return/sessions
Authorization: Bearer <Firebase ID token>
Content-Type: application/json

{"guardianIds":[2,3]}
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

## 선택 보호자 종료 정리

SafeReturnSessionService는 SelectedGuardianStore도 생성자로 주입받는다.
정상 종료 시 기존 소유권 검사와 두 활성 세션 인덱스 제거를 유지하면서,
같은 세션 UUID의 선택 보호자 목록도 삭제한다. 종료 실패 시 목록은 변경하지 않는다.
선택 저장의 활성 확인과 종료 정리는 동일 ActiveSessionStore 잠금으로 조정한다.
자세한 내부 계약과 테스트는 ../guardian-monitoring/README.md를 참고한다.
기존 REST URL, Request, Response 및 Firebase 인증 흐름은 변경하지 않는다.
## 사용자 전용 WebSocket 채널 연동

세션 시작은 사용자당 활성 세션 저장 성공 후 사용자만 참가하는 채널을 생성하고,
SessionChannelStore에 세션 UUID와 채널 ID를 매핑한다. 중복 요청은 채널을 추가 생성하지 않는다.
생성·매핑 실패는 새로 만든 리소스만 보상 정리하고 기존 INTERNAL_ERROR 응답으로 처리한다.
종료는 소유권 확인 후 채널 정리가 성공해야 매핑·활성 세션·선택 목록을 삭제한다.
채널 정리 실패 시 상태를 보존하여 종료 재시도를 허용한다.
API Request/Response와 ActiveSessionResponse(id, startedAt)는 유지하며 channelId는 공개하지 않는다.
SafeReturnSessionService 생성자는 ActiveSessionStore, SelectedGuardianStore, SessionChannelStore,
InMemoryChannelRegistry를 주입받는다. 모두 동일 애플리케이션 singleton을 사용한다.
실패 보상과 후속 연동 제약은 ../guardian-monitoring/README.md를 참고한다.
## 최신 위치 종료 정리

SafeReturnSessionService는 LatestLocationStore를 추가로 주입받는다.
정상 종료에서 선택 보호자 목록과 함께 해당 세션의 최신 위치 메모리도 삭제한다.
위치 갱신은 동일한 ActiveSessionStore 잠금 안에서 활성 상태와 소유자를 검증한다.
종료 실패 시 위치도 유지하고, 종료 완료 후 갱신은 거부한다.
기존 API 요청·응답과 WebSocket 중계 흐름은 변경하지 않는다.
## 시작 전 선택 보호자 내부 경로

새 startSession(Long, Collection<Long>)은 보호자 전체의 ACCEPTED·LOCATION을 검증한 뒤
세션·선택 목록·채널·매핑을 생성한다. 실패 보상은 선택 정보까지 정리한다.
기존 body 없는 API와 startSession(Long)은 팀 승인 전까지 기존 사용자 전용 동작을 유지한다.
신규 정책의 HTTP 요청 DTO와 오류 계약은 아직 반영하지 않는다.
SafeReturnSessionService는 GuardianSelectionService를 추가 주입받으며 반대 방향 의존성은 없다.
