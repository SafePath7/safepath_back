# 보호자 모니터링 기반 구조

## 범위와 구조

메모리 기반 선택 보호자 관리와 권한 조회 계약, GuardianMonitoringService를 구현한다.
REST API, 실제 보호자 권한 어댑터 및 위치 조회는 아직 없다.

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

`GuardianPermissionService`에는 구현체나 Spring Bean 등록이 없다.
팀원 Service의 실제 시그니처를 가정하지 않으며 팀 합의 후 어댑터를 연결한다.

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

현재 실제 GuardianPermissionService 구현체가 없어 GuardianMonitoringService는 @Service나 @Bean으로
등록하지 않는다. 필수 의존성은 생성자에서 null을 거부한다. 이 선택으로 기존 애플리케이션에
미충족 의존성을 추가하지 않는다. 팀원 PR 병합만으로 자동 활성화되지는 않으며,
실제 어댑터와 명시적 Bean 등록을 함께 연결해야 한다.

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