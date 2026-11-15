# 보호자 모니터링 기반 구조

## 현재 공개 REST 계약 (프론트 합의 반영)

이 절은 아래의 이전 단계 기록 중 REST 미공개·계약 미확정 설명을 대체한다.
시작 API는 `POST /api/safe-return/sessions`, JSON `{"guardianIds":[2,3]}`를 받아 `201 {id, startedAt, channelId}`를 반환한다. 실제 UUID·시작 시각·채널 ID를 사용한다. Body 없는 이전 요청은 400이므로 클라이언트 변경이 필요하다. 기존 소유자 상태 조회와 종료 계약은 유지한다.

모든 요청은 `Authorization: Bearer <Firebase ID token>`을 사용한다. 보호자는 본인의 토큰으로 아래 경로를 호출한다. `guardianId`를 JSON·쿼리로 받지 않으며 Firebase UID를 UserService에서 DB ID로 변환한다.

| 메서드와 경로 | 성공 응답 | 권한 |
|---|---|---|
| `GET /api/guardian/monitoring/sessions/{sessionId}/status` | `200 {id, startedAt}` | ACCEPTED + 선택 보호자 + STATUS |
| `GET /api/guardian/monitoring/sessions/{sessionId}/location` | `200 {latitude, longitude, accuracyMeters, capturedAt}` | ACCEPTED + 선택 보호자 + LOCATION |

`sessionId`는 시작 응답의 UUID다. 상태 응답은 활성 세션의 ID와 시작 시각이며 별도 status 필드는 없다. 위치 측정 시각은 ISO-8601 UTC, 정확도 단위는 미터이며 accuracyMeters는 null일 수 있다. 위치 미수신은 권한 검사 후 `204 No Content`다. 소유자 본인은 보호자용 API를 사용할 수 없으며 기존 소유자 상태 API를 사용한다. 이 API는 보호자에게 channelId를 공개하지 않는다.

```json
{"latitude":37.5,"longitude":127.0,"accuracyMeters":3.0,"capturedAt":"2026-11-15T09:00:00Z"}
```

기존 예외 규칙을 유지한다. 잘못된 UUID는 `400 INVALID_SESSION_ID`, 잘못된 시작 JSON·목록은 `400 INVALID_REQUEST`, 없는/종료된 세션은 `404 SESSION_NOT_FOUND`, 중복 시작은 `409 ACTIVE_SESSION_CONFLICT`, 조회 장애 등 예상하지 못한 실패는 `500 INTERNAL_ERROR`다. AccessDeniedException은 기존 `403 USER_NOT_REGISTERED`와 기존 메시지로 처리하므로 미가입과 보호자 권한 거부를 구분하지 않는다. 오류 본문은 `{code, message}`이며, 인증 필터 단계의 401/403은 기존대로 본문이 없을 수 있다. 오류 코드 세분화는 후속 계약 변경 대상으로 남긴다.

Postman에서는 먼저 소유자 토큰으로 세션을 시작한 뒤 반환된 id를 경로에 넣고, 선택된 보호자 토큰으로 GET을 호출한다. 보호자 ID는 실제 수락 관계와 LOCATION 권한이 있는 DB ID여야 하며 상태 조회에는 STATUS도 필요하다. 종료는 소유자 토큰으로 `PATCH /api/safe-return/sessions/{id}/end`를 호출한다. 실제 WebSocket 수신→Store 자동 저장은 아직 미연결이므로 수신 위치가 저장되지 않았다면 위치 GET은 204다. 테스트 목적으로 공개 위치 저장 API를 추가하지 않는다.

Controller는 GuardianMonitoringQueryService → GuardianMonitoringService로 연결하고 Store를 직접 조회하지 않는다. 시작 Controller도 기존 권한 검사된 getChannelId 메서드로 SessionChannelStore 매핑을 조회한다. WebSocket·JPA·DB 설정 변경은 없다.

이번 REST 연결 검증은 다음 명령으로 실행했다. 기존 160개를 유지하고 Controller 테스트 4개를 추가하여 대상 164개가 통과했다. 실패·오류·건너뜀 0개, main/test 컴파일 성공이다. 인증 토큰 검증과 권한 조회는 테스트 내부 mock을 사용하고 실제 필터·Controller·세션 Service·Store를 연결했다. Postman 실서버 검증은 아직 실행하지 않았다.

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest" --tests "com.safepath.backend.guardian.GuardianAccessTests"
```

## 범위와 구조

메모리 기반 선택 보호자 관리와 권한 조회 계약, GuardianMonitoringService를 구현한다.
실제 보호자 권한 어댑터와 모니터링·선택 Bean을 연결했다. REST API와 WebSocket 위치 수신 연결은 아직 없다. 내부 위치 조회와 선택 Service는 아래 후속 구현 절을 참고한다.

- `session/model/MonitoringPermissionType`: `STATUS`, `LOCATION`
- `session/store/SelectedGuardianStore`: 세션 `UUID`별 보호자 `Long` ID 집합
- `session/service/GuardianPermissionService`: 향후 보호자 담당 Service에 위임할 모니터링 측 인터페이스
- `session/service/GuardianMonitoringService`: 공통 권한 검증 및 상태 조회
- `session/service/GuardianMonitoringServiceTest`: 정상·거부·장애 및 권한 분리 테스트
- `session/store/SelectedGuardianStoreTest`: 저장소 단위 테스트

DB 테이블, Repository, 마이그레이션, 환경변수는 추가하지 않는다.
프로세스 재시작 시 데이터가 사라지며 여러 인스턴스 간 공유되지 않는다.

## 저장소 계약

| 메서드 | 역할 |
|---|---|
| `save(UUID sessionId, Set<Long> guardianIds)` | 선택 목록 전체 교체. 입력을 불변 복사한 뒤 원자적으로 저장 |
| `findBySessionId(UUID sessionId)` | 불변 스냅샷 반환. 미등록이면 빈 집합 |
| `isSelectedGuardian(UUID sessionId, Long guardianId)` | 선택 여부 반환. 미등록·빈 목록이면 false |
| `remove(UUID sessionId)` | 해당 목록 제거. 반복 호출 가능 |

세션 ID와 목록은 null을 허용하지 않으며, 보호자 ID는 null 또는 0 이하를 허용하지 않는다.
유효하지 않은 저장 요청은 기존 목록을 변경하지 않는다.
빈 집합 저장은 선택 취소를 의미한다. 이전 반환 스냅샷은 교체·삭제 후에도 변하지 않는다.

이 Store는 활성 세션 존재 여부를 검증하지만 인증·소유권·관계·열람 권한을 검증하지 않는다.
`isSelectedGuardian()`의 true만으로 모니터링 접근을 허용하면 안 된다.
향후 Service가 인증된 세션 소유자의 선택 요청만 받아야 한다.

## 권한 조회 인터페이스 제안

`GuardianPermissionService`의 실제 구현 Bean은 GuardianPermissionAdapterService다.
병합된 guardian.service.GuardianRegistrationService의 공개 메서드만 호출하며 Repository는 직접 조회하지 않는다.

- `hasAcceptedRelationship(Long userId, Long guardianId)`: 소유자 → 보호자 방향의 수락된 관계 확인
- `hasPermission(Long userId, Long guardianId, MonitoringPermissionType permissionType)`: 요청한 열람 권한 확인

양수인 DB 사용자 ID와 null이 아닌 권한 타입을 전달한다. 관계 없음 또는 권한 없음은 false,
조회 장애는 예외로 전파한다. 향후 호출자는 예외 시 접근을 허용하지 않아야 한다.
두 검사는 모두 통과해야 하며 STATUS와 LOCATION을 서로 대신할 수 없다.
항상 true인 임시 구현은 제공하지 않는다.

서비스 검증 순서: 입력 검증 → 활성 세션 조회 → 소유자 본인 차단 → 수락 관계 →
요청별 열람 권한 → 선택 보호자 확인 → 검증된 ActiveSession 반환.
기존 소유자 전용 `getSessionStatus()`는 변경하지 않는다.

## 모니터링 서비스와 인증 경계

- `getMonitoringStatus(UUID sessionId, Long guardianId)`: STATUS 검증을 호출하여 기존 세션 반환
- `validateMonitoringPermission(UUID sessionId, Long guardianId, MonitoringPermissionType permissionType)`:
  UUID 필수, guardianId 양수, 권한 타입 필수를 검증하고 위 순서대로 접근 조건 확인

세션 미존재는 NoSuchElementException, 접근 거부는 AccessDeniedException,
잘못된 인자는 IllegalArgumentException이다. 의존 Service 장애는 그대로 전파하며 데이터를 반환하지 않는다.
이는 내부 Java 예외이며 HTTP 상태·오류 코드를 확정한 것이 아니다.
각 조회는 스냅샷이며 관계·권한·선택·종료 사이의 원자성은 아직 보장하지 않는다.

GuardianPermissionAdapterService, GuardianMonitoringService, GuardianSelectionService는 @Service로 등록한다.
실제 GuardianRegistrationService를 생성자 주입하며 임시 허용 구현을 제공하지 않는다.

이 서비스의 guardianId는 내부 호출 인자이며 인증 증명이 아니다. 향후 Controller는 기존 Firebase
필터가 검증한 principal의 UID를 UserService로 DB ID에 변환하여 전달해야 한다.
클라이언트의 guardianId를 신뢰해서는 안 된다. 현재 Controller나 HTTP 경로를 공개하지 않았으므로
Firebase 인증을 포함한 보호자 API 연동은 아직 완료되지 않았다.

ActiveSessionResponse.from()은 id와 startedAt을 변환할 수 있지만 Notion의 상세 응답이
확정되지 않아 모니터링 응답 DTO로 채택하지 않는다. status 필드를 임의로 추가하지 않는다.

사용자가 확인한 경로는 GET /api/guardian/monitoring/sessions/{sessionId}/status 및
GET /api/guardian/monitoring/sessions/{sessionId}/location이다. 두 경로 모두 아직 구현하지 않았다.
요청·응답 JSON과 HTTP 오류 계약 확인 후 상태 Controller를 연결한다.
위치 계약은 LOCATION 권한 검증 후 메모리 LocationService에서 최신 좌표와 측정 시각을 조회하는
방향만 검토했다. 좌표 타입·측정 시각 타입·미수신·오래된 위치 처리 및 세션별 조회 키는
위치 담당자와 합의해야 하며 메서드나 DTO를 임의로 추가하지 않는다.

## 세션 종료와 선택 저장의 동시성

`SafeReturnSessionService.endSession()`은 `removeIfOwned()` 결과가 REMOVED일 때만
동일 sessionId의 선택 목록을 삭제한다. NOT_OWNER 및 NOT_FOUND이면 목록을 변경하지 않는다.
다른 활성 세션의 목록에도 영향을 주지 않는다.

`SelectedGuardianStore`는 동일한 Spring singleton ActiveSessionStore를 생성자로 주입받는다.
선택 목록 저장은 불변 복사와 ID 검증 후 ActiveSessionStore 잠금 안에서 활성 여부를
확인하고 저장한다. 미존재·종료된 세션 저장은 NoSuchElementException으로 거부한다.
세션 종료도 같은 잠금 안에서 소유권 확인·세션 제거·선택 목록 삭제를 완료한다.
따라서 저장이 먼저면 종료가 삭제하고, 종료가 먼저면 후속 저장이 거부된다.
동시성 제어를 위해 종료 UUID를 별도 누적 보관하지 않는다.

ActiveSessionStore의 기존 메서드와 synchronized 구조는 변경하지 않았다.
Store를 직접 remove()/removeIfOwned()하여 Service를 우회하는 내부 코드는 선택 목록 정리를
수행하지 않는다. 실제 종료 경로는 endSession()을 사용해야 한다.
직접 생성하는 테스트에서도 두 Store와 Service에 동일한 ActiveSessionStore를 전달해야 한다.
이 잠금은 단일 프로세스에서만 유효하고, 모든 세션의 짧은 메모리 작업을 직렬화한다.
Firebase 인증·소유자 선택 권한·수락 관계·열람 권한 검증은 향후 선택 Service에서 수행해야 한다.

## 사용 예시와 테스트

HTTP 요청/응답은 추가하지 않았으므로 이번 기능의 Postman 테스트는 불가능하다.
단위 테스트는 Spring context, Firebase 또는 DB 연결 없이 저장소를 직접 생성한다.
테스트의 예시 보호자 ID는 42, 43, 44이며 세션 UUID는 임의 생성한다.

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.store.SelectedGuardianStoreTest"
```

검증 범위: 세션별 분리, 빈 목록·미등록 거부, 전체 교체, 입력 방어적 복사,
반환 불변성, 반복 삭제, null·잘못된 ID 거부, 잘못된 교체 시 기존 값 보존.
실제 실행 결과 (2026-11-14): 위 명령으로 7개 테스트 실행, 실패 0, 오류 0, 건너뜀 0,
`BUILD SUCCESSFUL`. 최초 실행은 Gradle 사용자 캐시 잠금 파일 접근 제한으로 테스트 시작 전에
중단되었으며, 캐시 접근 허용 후 재실행하여 통과했다.
기존 실행 경로를 변경하지 않아 별도 회귀 테스트는 실행하지 않았다.

### 2단계 실행 결과 (2026-11-14)

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.service.GuardianMonitoringServiceTest" --tests "com.safepath.backend.session.store.SelectedGuardianStoreTest" --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest" --tests "com.safepath.backend.session.controller.SafeReturnSessionControllerTest"
```

실제 결과: BUILD SUCCESSFUL, 총 51개 통과, 실패·오류·건너뜀 0.
모니터링 11개, 선택 Store 7개, 기존 세션 Service 15개, 활성 Store 9개, 세션 Controller 9개.
모니터링 테스트는 실제 메모리 Store와 세션 Service를 사용하고 권한 Service만 테스트 내부에서 mock한다.
정상 조회, 미수락·권한 없음·미선택·소유자 거부, 종료·미존재, 두 권한 방향 분리,
관계·권한 조회 장애 전파, 입력 검증을 확인했다.
최초 실행은 Gradle 캐시 접근 제한으로 시작 전에 중단되었고 접근 허용 후 재실행했다.
전체 애플리케이션 기동, 실제 Firebase·DB 및 보호자 API 통합 테스트는 실행하지 않았다.

## 다음 합의 사항

- 보호자 담당 PR: 수락 관계 및 권한 Service의 실제 메서드와 오류 계약
- API 계약: 소유자 전용 선택 입력 경로, 변경 시점, 상태 응답 및 오류 형식
- 위치 담당 PR: 메모리 LocationService의 최신 위치 조회 계약 및 위치 미수신 처리
- WebSocket 담당자: Firebase 주체 연결, 구독 인가, 세션–채널 연결 및 종료 정리

관계 취소·권한 변경 시 재검증, 종료와 선택 변경 동시성은 후속 통합 검증 대상이다.

## 종료 정리 구현 검증 결과

실행 명령 (safepath_back 디렉터리):

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.*"
```

실제 결과: BUILD SUCCESSFUL, 총 55개 통과, 실패·오류·건너뜀 0.
세션 Service 18개, 활성 Store 9개, 선택 Store 8개, 모니터링 Service 11개, 세션 Controller 9개.
추가 검증: 정상 종료 정리와 다른 세션 격리, 종료 실패 시 보존,
저장·종료 동시 실행 후 잔여 목록 없음, 비활성 세션 저장 거부.
기존 생성자 변경에 맞춰 단위 테스트와 Controller 테스트의 Bean 구성을 함께 갱신했다.
실제 Firebase·DB 통합 또는 Postman 테스트는 실행하지 않았다.
## 사용자 전용 세션–채널 연동

추가 구조:
- session/store/SessionChannelStore: UUID → channelId 메모리 매핑
- session/exception/SessionChannelException: 채널 수명주기 실패를 중복 시작과 구분
- session/service/SessionChannelLifecycleTest 및 store/SessionChannelStoreTest: 실패 보상·재시도·동시성 검증

SessionChannelStore.save(UUID, String)는 putIfAbsent로 중복 매핑을 거부한다.
findBySessionId(UUID)는 Optional<String>, remove(UUID)는 삭제된 Optional<String>을 반환한다.
removeIfMapped(UUID, String)는 값이 일치할 때만 삭제하여 다른 매핑을 덮어쓰거나 지우지 않는다.
null UUID, null 또는 공백 channelId 저장은 거부한다. 단일 프로세스 전용이며 재시작 시 사라진다.
이 Store는 내부 저장 수단으로, 공개 메서드 자체가 사용자 인증이나 활성 세션을 검증하지 않는다.

startSession()은 기존 사용자당 중복 검사에 성공한 뒤 open(userId, Set.of())로 사용자 전용 채널을
만들고 매핑을 저장한다. 생성·매핑 실패 시 이번 요청의 채널과 세션만 정리한다.
전체 시작·종료 처리는 선택 저장과 동일한 ActiveSessionStore 잠금을 사용한다.
이미 활성 세션이 있으면 open()에 도달하지 않아 추가 채널이 생성되지 않는다.

endSession()은 세션 존재·소유권을 먼저 확인하고 채널 close()가 성공한 뒤 매핑·활성 세션·선택 목록을
제거한다. close() 실패 시 이 정보들을 보존하므로 같은 종료 API로 재시도할 수 있다.
예전 내부 경로에서 생성되어 매핑이 없는 활성 세션도 기존 방식으로 종료할 수 있다.
close()는 채널 Registry만 지우며 WebSocket 연결·브로커 구독을 직접 종료하지 않는다.

시작 실패 보상 중 close()도 실패하면 세션과 채널 ID를 보존해 종료 재시도를 지원한다.
매핑 저장 자체와 보존 재시도까지 모두 실패한 다중 장애는 자동 복구를 보장하지 않는다.
원래 실패와 후속 정리 실패는 예외 원인·suppressed exception으로 유지한다.
현재 실제 Registry와 Store는 외부 I/O를 하지 않는다. open() 내부에서 실패하여 ID를 반환하지 않은
리소스는 호출자가 정리할 수 없으므로 향후 Registry가 변경되면 생성의 실패 원자성도 유지해야 한다.

SessionChannelException은 RuntimeException으로 기존 INTERNAL_ERROR / 500 응답을 사용한다.
정상 중복 시작 IllegalStateException은 기존 ACTIVE_SESSION_CONFLICT / 409를 유지한다.
새 HTTP 응답 필드·오류 코드·channelId 조회 API는 추가하지 않는다.
ActiveSession과 ActiveSessionResponse 및 기존 API 요청·응답·인증은 변경하지 않는다.
보호자 선택 목록이 있어도 채널에는 소유자만 등록한다. 보호자 실제 위치 공유는 활성화하지 않는다.

후속 작업: 선택·LOCATION 검증과 WebSocket 참가자 갱신, channelId 전달 API,
구독 권한 및 취소 처리. 이번 연동에서는 팀원 WebSocket 코드와 경로·DTO를 수정하지 않는다.
### 세션–채널 연동 테스트 결과

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest"
```

실제 결과: BUILD SUCCESSFUL, 세션 69개 + WebSocket 12개 + Security 3개 = 총 84개 통과.
실패·오류·건너뜀 0. main/test 전체 소스 컴파일 성공.
정상 시작·종료, 사용자 전용 참가자, 다른 세션 격리, 중복·동시 시작의 채널 1개 생성,
채널 생성 실패·매핑 실패(저장 전/후)의 보상, 종료 실패 후 재시도,
시작 보상 중 close 실패 후 종료 재시도, 채널 실패의 기존 HTTP 500 응답을 검증했다.
Mockito는 테스트 내부에서만 사용한다. 실제 Firebase ID 토큰을 이용한 프론트 수동 테스트는 미실행이다.
## 최신 위치 메모리 관리와 보호자 선택

### 추가 구조

- session/store/LatestLocationStore: 세션 UUID별 불변 LocationUpdateRequest 스냅샷 메모리 관리
- session/service/GuardianSelectionService: 소유자 전용 선택·해제, 수락 관계 검증
- GuardianMonitoringService.getMonitoredLocation(UUID, Long): 기존 LOCATION 권한 검증 후 최신 위치 반환

기존 getMonitoringStatus()와 validateMonitoringPermission()은 재사용하며 중복 구현하지 않는다.
로컬에 승인된 Notion 상세 요청·응답 JSON과 오류 계약이 없어 새 Controller는 추가하지 않는다.
상태 조회 URL과 위치 조회 URL은 앞서 확인된 경로만 유지하고 이번에도 공개하지 않는다.

### 위치 내부 계약

updateLocation(UUID sessionId, Long userId, LocationUpdateRequest location)은 활성 세션 소유자만 허용한다.
userId는 향후 연결 계층이 Firebase 인증 주체에서 확인한 DB ID여야 한다. 단순 ID 인자는 인증 증명이 아니다.
위도·경도 범위와 유한값, 정확도 0 이상 유한값(또는 null), capturedAt 필수를 직접 검사한다.
Bean Validation에만 의존하지 않아 내부 Java 호출에서도 잘못된 값을 저장하지 않는다.

기존 WebSocket 요청 record는 불변이며 좌표·정확도·측정 시각을 담으므로 내부 스냅샷으로 재사용한다.
LocationUpdateResponse는 채널·송신자·서버 수신 시각을 포함한 중계 응답이므로 REST 계약으로 채택하지 않는다.
측정 시각 capturedAt이 더 최신일 때만 교체한다. 과거·동일 시각 데이터는 기존 값을 유지한다.
위치 미수신은 활성 세션에 대해 Optional.empty(), 비활성 세션 조회·갱신은 NoSuchElementException이다.
이는 내부 계약이며 HTTP 응답·오류·만료 기준이나 미래 측정 시각 허용 정책을 확정한 것이 아니다.

LatestLocationStore.findBySessionId()는 내부 저장소 조회 수단이다. 보호자 조회는 반드시
GuardianMonitoringService.getMonitoredLocation()으로 수락 관계·LOCATION·선택 상태를 검증해야 한다.
getMonitoredLocation()은 검증과 조회 동안 세션 잠금을 유지한다. 권한 조회 장애는 전파하고 위치를 반환하지 않는다.
안전귀가 정상 종료 시 위치를 삭제하며, 채널 정리 실패 등 종료 실패 시에는 재시도를 위해 위치도 유지한다.
종료와 갱신은 같은 ActiveSessionStore 잠금을 사용하여 종료 후 위치가 다시 저장되는 경쟁을 막는다.
단일 서버 메모리 전용이며 DB·JPA·마이그레이션·환경변수는 추가하지 않는다.

### 보호자 선택 내부 계약

selectGuardian(UUID sessionId, Long ownerId, Long guardianId)는 기존 getSessionStatus()로 활성·소유권을
확인한 뒤 자기 자신 선택을 거부하고 GuardianPermissionService.hasAcceptedRelationship()를 호출한다.
수락된 보호자만 기존 SelectedGuardianStore에 추가하며, 중복 선택은 목록을 중복시키지 않는다.
선택은 열람 허가 자체가 아니다. STATUS·LOCATION은 각각 실제 조회 시 기존 검증으로 확인한다.

deselectGuardian(UUID sessionId, Long ownerId, Long guardianId)는 소유자만 수행할 수 있다.
반복 해제·미선택 해제는 동일한 결과다. 관계가 이미 취소되어도 제거할 수 있도록 해제에는 관계 조회가 필요 없다.
선택·해제·종료는 같은 세션 잠금으로 조정한다. 다른 사용자는 목록을 변경할 수 없다.
진행 중 선택 변경은 아래 확정 정책에 따라 차단한다. 보호자 목록을 받는 내부 시작 경로에서만 선택과 채널 참가자를 함께 생성한다.

GuardianSelectionService와 GuardianMonitoringService는 실제 권한 어댑터를 주입받는 Bean이다.
운영 코드에 mock 또는 임시 허용 구현은 없다.

### 아직 연결하지 않은 부분

- 김규식 담당 위치 수신: 인증된 송신자의 DB ID와 채널 ID → 안전귀가 UUID를 확인하여 updateLocation() 호출
- WebSocket 기존 relay()·인터셉터·경로·DTO는 변경하지 않음. 현재 실제 수신 위치는 이 Store로 자동 저장되지 않음
- 권한 조회를 수행하는 동안 세션 잠금을 유지하므로 추후 실제 DB 조회 지연과 종료의 상호작용 검토 필요
- 프론트 API: 선택·해제 경로, 요청·응답, 상태 JSON, 위치 미수신·오류 응답 확정
- 관계 변경·선택 해제 후 기존 WebSocket 구독 차단과 채널 참가자 갱신은 김규식 담당자와 후속 합의
### 최신 위치·선택 기능 실행 결과 (2026-11-15)

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest"
```

실제 결과: BUILD SUCCESSFUL, 총 106개 통과, 실패·오류·건너뜀 0, main/test 소스 컴파일 성공.
기존 84개 + LatestLocationStoreTest 7개 + GuardianSelectionServiceTest 8개 + MonitoredLocationTest 7개.
정상·권한 거부·조회 장애·위치 역순·소유자 제한·종료 정리·종료 실패 시 보존·동시 요청을 검증했다.
새 권한 테스트 mock은 테스트 내부에만 존재한다. 실제 Firebase·팀원 권한 Service를 이용한
새 기능의 end-to-end 검증과 Postman 테스트는 미실행이며 Controller 계약 확정 후 수행한다.
## 실제 보호자 권한 어댑터와 Bean 연결

추가 파일: session/service/GuardianPermissionAdapterService.java.
기존 GuardianPermissionService 인터페이스를 유지하고 생성자로 GuardianRegistrationService를 주입한다.

| 어댑터 메서드 | 실제 팀원 Service 위임 |
|---|---|
| hasAcceptedRelationship(userId, guardianId) | getGuardianAccess(userId, guardianId).accepted() |
| hasPermission(userId, guardianId, STATUS) | hasGuardianPermission(userId, guardianId, PermissionType.STATUS) |
| hasPermission(userId, guardianId, LOCATION) | hasGuardianPermission(userId, guardianId, PermissionType.LOCATION) |

userId는 보호 대상자, guardianId는 보호자 순서다. null·0 이하 ID와 null 권한 타입은 호출 전에 거부한다.
수락 상태와 개별 권한 판정은 팀원 Service가 담당한다. 어댑터는 반환된 false를 그대로 전달하고
조회 예외를 잡아 허용으로 바꾸지 않는다. Repository 직접 조회·권한 판정 중복 구현은 없다.
세 Service 모두 @Service로 등록하고 기존 생성자 주입을 유지한다. GuardianPermissionService 구현은 하나다.

테스트:
- GuardianPermissionAdapterServiceTest: Service mock을 사용한 ID 순서·enum·허용/거부·조회 예외·입력 검증
- GuardianPermissionBeanTest: 실제 component scan, 실제 어댑터와 GuardianRegistrationService,
  모니터링·선택·수명주기 Bean을 연결. Repository와 알림 의존성만 테스트 내부 mock 사용
- 기존 SpringBoot WebSocket/Security 통합 테스트: 실제 애플리케이션 구성과 신규 Bean 기동 회귀

Bean 연결 테스트는 실제 JPA Repository 쿼리를 검증하는 DB 통합 테스트가 아니다.
REST API·응답·WebSocket 경로·DB/Flyway는 이번 변경 범위에 없다.
### 실제 권한 어댑터 실행 결과 (2026-11-15)

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest" --tests "com.safepath.backend.guardian.GuardianAccessTests"
```

실제 결과: BUILD SUCCESSFUL, 총 120개 통과, 실패·오류·건너뜀 0.
기존 106개 유지 + 신규 어댑터 7개 + Bean 연결 1개 + 팀원 GuardianAccessTests 6개.
전체 main/test 컴파일과 신규 Bean을 포함한 SpringBoot 기동 회귀 통과.
권한 어댑터와 실제 JPA DB 데이터의 end-to-end 검증 및 새 REST 수동 테스트는 실행하지 않았다.
## 확정 정책: 시작 전 보호자 선택 (2026-11-15)

현재 정책은 이 절을 기준으로 한다. 이전 절의 진행 중 선택·해제 설명은 과거 구현 기록이다.

- 신규 내부 메서드: SafeReturnSessionService.startSession(Long userId, Collection<Long> guardianIds)
- GuardianSelectionService.validateForStart(Long ownerId, Collection<Long> guardianIds): 전체 검증 후 불변 Set 반환
- GuardianSelectionService.getSelectedGuardians(UUID sessionId, Long ownerId): 활성 세션 소유자만 불변 목록 조회
- selectGuardian()/deselectGuardian() 시그니처는 유지하지만 활성 세션의 목록 변경은 거부

null·빈 목록·null/0 이하 ID·본인 선택을 거부한다. 중복 ID는 하나로 정규화하고 각 보호자는 한 번씩 검증한다.
모든 보호자에 대해 실제 어댑터의 ACCEPTED 관계 및 LOCATION 권한을 검사한다. STATUS만으로는 시작 불가하다.
한 명이라도 실패하거나 조회 예외가 발생하면 어떤 세션 리소스도 생성하지 않는다.

순서: 전체 검증 → 기존 중복 세션 검사와 세션 저장 → 선택 목록 저장 → open(userId, guardianIds) → 매핑 저장.
중복 시작은 open() 전에 거부한다. 생성 구간과 종료·선택·위치 메모리 갱신은 같은 세션 잠금을 사용한다.
시작 중 선택 저장·채널 생성·매핑 실패 시 이번 요청의 선택 목록·위치·세션과 생성된 채널·매핑을 보상 정리한다.
채널 보상 close까지 실패하면 기존 재시도 보존 방침에 따라 세션·선택·매핑을 남긴다.
매핑 보존도 함께 실패하는 다중 장애의 자동 복구 한계는 기존 문서와 같다.
권한 검증과 메모리 생성은 DB와 메모리 사이의 단일 트랜잭션이 아니며, 생성 직후 권한 취소는 구독 단계의 후속 검증이 필요하다.

SafeReturnSessionService가 GuardianSelectionService를 주입받도록 변경했다.
순환 의존성을 막기 위해 SelectionService는 SafeReturnSessionService를 참조하지 않고 활성 Store에서 소유권만 검사한다.
관계·LOCATION 판정은 계속 어댑터 → 팀원 Service를 통해 수행한다. JPA Repository를 직접 조회하지 않는다.

### 공개 API 호환성과 승인 요청

기존 startSession(Long userId)와 body 없는 POST는 그대로 유지하며 사용자 전용 채널을 생성한다.
이 경로는 기존 계약을 보존하기 위한 기존 동작이며, 신규 보호자 필수 정책이 적용된 경로가 아니다.
새 목록 기반 시작 메서드는 아직 HTTP에서 호출하지 않는다. Controller·DTO·HTTP 상태·오류 코드 변경은 하지 않았다.

팀 승인용 StartSessionRequest 후보는 List<Long> guardianIds를 가진 요청 DTO다. 목록은 최소 한 명,
각 항목은 null이 아닌 양수이며 서버에서도 관계·권한 전체 검증을 수행한다.

```json
{"guardianIds":[43,44]}
```

중복 ID는 정규화한다. 본인 ID와 미수락·LOCATION 미허용 ID는 전체 거부한다.
body 없는 기존 클라이언트 요청은 현재 동작을 유지한다. 전환일 이후 거부할지, 별도 호환 기간을 둘지 팀 승인이 필요하다.
프론트는 시작 전에 선택 화면을 완료하고 목록을 제출하며, 일부 실패 시 목록을 수정해 다시 요청해야 한다.
진행 중 편집 UI는 제공하지 않는다. 새 응답에 channelId를 추가하는 결정은 별도 계약이다.

모니터링 상태 응답 후보: 기존 id·startedAt 재사용 또는 별도 MonitoringStatusResponse.
status 필드가 필요하면 값의 정의를 먼저 승인받는다.
위치 응답 후보: MonitoredLocationResponse(latitude, longitude, accuracyMeters, capturedAt).
위치 없음·오래된 위치·서버 수신 시각 포함 여부는 미확정이다. DTO 클래스는 아직 추가하지 않는다.

오류 최상위 형식은 {code, message}를 유지한다. 빈 목록·권한 실패·미가입·미존재·조회 장애·위치 없음의
HTTP 상태와 code/message는 별도 승인 사항이다. 기존 중복 시작 409와 기존 공개 응답은 유지한다.

### WebSocket 후속 및 보안 확인 사항

이번 연결은 open()에 검증된 참가자 집합을 전달하는 범위다. 실제 다중 보호자 수신 테스트는 후속이다.
기존 인터셉터는 지정된 prefix/suffix의 SUBSCRIBE만 검사한다. 다른 목적지·와일드카드 구독과
/topic 직접 SEND에 대한 기본 거부가 코드에서 확인되지 않아 보안 보강 및 재현 검증이 필요하다.
관계·LOCATION 권한 취소 후 기존 구독 차단도 미구현이다. 이번에는 팀원 인터셉터를 수정하지 않았다.
### 시작 전 선택 정책 검증 결과

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest" --tests "com.safepath.backend.guardian.GuardianAccessTests"
```

2026-11-15 실제 최종 결과: BUILD SUCCESSFUL, 134개 통과, 실패·오류·건너뜀 0.
기존 120개 테스트 수 유지 + GuardianSessionStartTest 14개 신규.
기존 선택 8개 테스트는 진행 중 변경 거부·시작 전 검증·소유자 조회 정책으로 갱신했다.
Bean 연결 테스트도 실제 어댑터를 통한 새 목록 기반 시작을 사용하도록 변경했다.
생성자 변경에 따른 기존 테스트 의존성 구성도 갱신했다. 기존 공개 API 회귀는 유지한다.
최초 compileTestJava는 위치 테스트 spy 인자 수정 실수로 실패했고 바로잡은 뒤 재실행하여 통과했다.
새 REST/프론트·실제 다중 보호자 WebSocket 수신 검증은 아직 수행하지 않았다.

## REST 연결 준비와 WebSocket 보안 검토

내부 인증 주체 연결 Service와 API 계약·보안 분석은 [rest-security-review.md](rest-security-review.md)를 참고한다.
공개 Controller나 REST 경로는 아직 추가하지 않았다.

## 세션–채널 조회 및 위치 저장 내부 경계 (2026-11-15)

신규 Service를 만들지 않고 기존 세 Service에 메서드를 추가했다.

- GuardianMonitoringQueryService.getChannelId(UUID, Authentication): 기존 Firebase 인증 경계를 재사용
- GuardianMonitoringService.getChannelId(UUID, Long): 활성 세션 확인 후 소유자는 허용,
  보호자는 기존 validateMonitoringPermission(..., LOCATION)의 ACCEPTED·LOCATION·선택 검증 후 매핑 반환
- SafeReturnSessionService.updateLocation(String channelId, Long publisherId, LocationUpdateRequest):
  인증된 송신자 DB ID로 활성 세션을 찾은 뒤 아래 명시적 세션 검증 메서드에 위임
- SafeReturnSessionService.updateLocation(UUID, String channelId, Long publisherId, LocationUpdateRequest):
  활성·소유권·세션–채널 매핑 일치·Registry 송신자 검증 후 기존 LatestLocationStore에 위임

HTTP 요청자는 Authentication에서만 결정한다. 내부 Long 인자와 WebSocket publisherId는
검증된 서버 측 신원이며 클라이언트 body/query/header의 ID를 전달하면 안 된다.
보호자가 자기 활성 세션을 갖고 있어도 다른 소유자의 채널에는 위치를 저장할 수 없다.
이미 종료된 세션, 매핑 없는 세션, 잘못된 채널, 닫힌 채널의 위치 저장은 거부한다.
조회 실패·권한 없음은 channelId를 반환하지 않는다. 새 HTTP 상태·오류 코드는 정의하지 않았다.

조회와 저장은 기존 ActiveSessionStore 잠금을 종료와 공유한다.
LOCATION 검증 후 매핑 조회와 종료가 중간에 끼어들지 않도록 한다. 반환된 channelId는 조회 시점의
스냅샷이므로 반환 직후 종료될 수 있다. 구독 단계에서도 별도 재검증해야 한다.
위치 갱신이 먼저면 종료가 정리하고, 종료가 먼저면 저장을 거부한다.
기존 capturedAt 역순 덮어쓰기 방지, 위치 유효값 검증, 종료 실패 시 재시도 보존 정책을 유지한다.
조회는 SessionChannelStore의 기존 UUID → String 매핑을 재사용하며 역방향 Store를 추가하지 않는다.
DB·JPA·Flyway 쓰기를 추가하지 않는다.

### 김규식 담당 relay와 연결할 최소 변경 제안 (아직 적용하지 않음)

위치: websocket/LocationUpdateController.relay()에서 participantIdForSession()으로 확인한
participantId와 isLocationPublisher() 검사가 성공한 직후, new LocationUpdateResponse(...) 직전.

```java
safeReturnSessionService.updateLocation(connectionId, participantId, request);
```

Controller에는 SafeReturnSessionService 생성자 주입만 추가하면 된다.
입력 sessionId를 클라이언트가 보내도록 DTO를 바꿀 필요가 없다. Service가 인증된 publisherId로
해당 사용자의 활성 세션을 찾고 channelId 매핑을 검증한다. 기존 채널을 그대로 재사용한다.
저장 실패 시 relay 응답을 생성하지 않고 예외를 전파하는 방향을 협의해야 한다.
오래된 측정값은 Store에서 무시되지만 기존 relay가 요청값을 중계하는 정책은 이번에 변경하지 않았다.
저장 메서드가 잠금을 해제한 뒤 종료와 중계가 경합할 수 있으므로 저장 정합성 보장은 종료 후
메시지 수신까지 차단한다는 의미가 아니다. 구독 종료·진행 중 메시지는 별도 WebSocket 수명주기 범위다.

이번 작업에서는 WebSocket 운영 코드·DTO·경로·공개 REST를 수정하지 않았다.
실제 수신 위치의 자동 저장은 위 한 번의 연결 변경이 적용되기 전까지 활성화되지 않는다.
기존 목적지 기본 거부·권한 취소 후 구독 차단 문제는 rest-security-review.md에 기록한 후속 과제다.

### 테스트

SessionChannelBoundaryTest는 실제 어댑터와 GuardianRegistrationService를 사용하고 Repository만
테스트 mock으로 대체한다. 소유자·선택 보호자·미선택·관계 취소·STATUS만 허용·조회 장애,
위치 정상 갱신·역순·소유자 제한·매핑 불일치·종료 정리·동시성·다른 세션 보존을 검증한다.
GuardianMonitoringQueryServiceTest에는 실제 Firebase 필터를 통한 채널 조회 신원 매핑을 추가했다.
기존 테스트를 삭제하거나 비활성화하지 않았다. 실제 JPA 데이터와 Firebase를 함께 사용하는 새 E2E 테스트는 아니다.
실제 실행 명령:

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest" --tests "com.safepath.backend.guardian.GuardianAccessTests"
```

결과: BUILD SUCCESSFUL, 기존 141개 + 신규 채널 경계 14개 + 인증 경계 1개 = 156개 통과.
실패·오류·건너뜀 0, main/test 컴파일 성공. WebSocket Controller 연결·실제 다중 수신 E2E는 미실행이다.
### 다중 보호자 내부 조회 검증 (후속 작업)

`SessionChannelBoundaryTest`에 Service 수준 테스트 4개를 추가했다. 같은 세션의 선택 보호자 B·C가 동일한 위치 스냅샷과 channelId를 조회하며, 미선택·LOCATION 권한 취소·관계 취소 시 조회를 거부한다. 측정 시각 역전 방지, 종료 후 조회 차단, 다른 세션 데이터 보존도 검증한다. 실제 B·C의 WebSocket 동시 수신 테스트는 아니다.

기존 `SafeReturnSessionService.updateLocation(channelId, publisherId, request)`와 WebSocket DTO를 그대로 사용한다. 운영 Service·WebSocket 파일은 추가 변경하지 않았다. 기존 156개와 신규 4개, 총 160개 테스트의 실패·오류는 0이다.

현재 위치 중계에는 소유권 검증 이후 발행하는 위치 이벤트나 공용 수신 Service가 없다. `@SendTo`는 응답 중계 설정이며 저장용 이벤트가 아니다. 따라서 기존 확장 지점만으로 자동 저장을 연결할 수 없다. 별도 인터셉터나 AOP로 중복 처리하지 않는다.

후속 연결은 `LocationUpdateController.relay()`의 `isLocationPublisher()` 검증 성공 직후, 응답 생성 전에 기존 `updateLocation(connectionId, participantId, request)`를 호출하는 방식이다. 담당자 협의 후 생성자 주입과 호출을 적용해야 한다. 저장 실패 시 중계 정책, 오래된 요청의 중계 여부는 별도 합의가 필요하다. Store의 역순 덮어쓰기 방지가 WebSocket 송신 순서까지 보장하지는 않는다.
