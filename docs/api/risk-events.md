# 위치·위험 이벤트·미디어 API

`feature/risk-event-api`는 `develop` 기준의 위치 2개, 위험 이벤트 2개, 미디어 3개 API를 구현합니다. 보호자 등록 API를 포함하지 않습니다.

보호자의 위치 조회 권한은 기존 `guardian_registrations`에서 방향(user_id → guardian_id), ACCEPTED 상태, `can_view_location=1`을 확인합니다. 보호자 기능 클래스에 의존하지 않고 해당 조회만 수행합니다. 기존 V1 정의와 요청·응답 형식을 유지하고, 위치 데이터는 기존에 작성한 V2를 그대로 포함합니다.

## 공통 전제

- 기본 주소는 `http://localhost:8080`입니다. 운영에서는 HTTPS를 사용합니다.
- 모든 요청은 `Authorization: Bearer <Firebase ID token>`을 사용합니다.
- 실제 토큰 검증은 공통 담당자가 제공하는 `FirebaseIdTokenVerifier` Bean이 담당하며, 현재 실제 구현체는 연결되지 않았습니다. 이번 코드는 이 검증기를 호출하는 필터와 UID·관리자 권한 연결을 구현합니다. JWT 서명·만료·issuer·audience 검증 로직은 새로 만들지 않았습니다.
- 검증기가 없거나 검증에 실패하면 401입니다. 요청 본문의 `userId`나 임의 UID·관리자 헤더를 인증에 사용하지 않습니다.
- 검증된 UID의 `users` 행이 미리 있어야 합니다. 미가입 UID는 404입니다. 회원 가입 API는 이번 명세에 없습니다.
- `sessionId`는 DB `safe_return_sessions`에 먼저 존재하는 UUID여야 합니다. 세션 생성 및 메모리 세션의 DB 저장은 세션 담당자와 연결해야 합니다.
- 시각 입력은 오프셋이 있는 ISO 8601 문자열입니다. 예: `2026-11-08T10:00:00+09:00`. 새 API는 기존 TIMESTAMP 컬럼을 UTC로 저장·해석하고 응답을 UTC `Z` 형식으로 반환합니다.


## 위치와 위험 이벤트

| 이름 | 메서드·경로 | 허용 사용자 | 성공 응답 |
|---|---|---|---|
| `saveLocation` | POST `/api/locations` | 세션 소유자 | 201, 위치 객체 |
| `getLatestLocation` | GET `/api/sessions/{sessionId}/locations/latest` | 소유자 또는 수락된 위치 조회 권한의 보호자 | 200, 위치 객체 |
| `createEvent` | POST `/api/risk-events` | 세션 소유자 | 201, 이벤트 객체 |
| `updateEventStatus` | PATCH `/api/risk-events/{eventId}/status` | 소유자 또는 관리자 | 200, 이벤트 객체 |

위치 저장 JSON:

```json
{
  "sessionId":"20b32680-0585-4f57-801e-d9568707d3ea",
  "latitude":37.5,
  "longitude":127.0,
  "recordedAt":"2026-11-08T10:00:00+09:00"
}
```

네 값 모두 필수입니다. 위도 -90~90, 경도 -180~180의 유한한 숫자만 허용하며 NaN/Infinity는 거절합니다. 최신 위치는 `recorded_at DESC, id DESC`로 정해 늦게 도착한 과거 기록이 최신 위치를 덮어쓰지 않습니다. 세션은 있지만 위치가 없으면 404입니다. 관리자 권한만으로 위치 조회를 우회하지 않습니다.

위치 응답 필드: `id`, `sessionId`, `latitude`, `longitude`, `recordedAt`, `createdAt`.

이벤트 생성 JSON:

```json
{
  "sessionId":"20b32680-0585-4f57-801e-d9568707d3ea",
  "eventType":"SOS",
  "description":"사용자 도움 요청",
  "detectedAt":"2026-11-08T10:00:00+09:00"
}
```

네 값 모두 필수입니다. 기존 DB 길이에 맞춰 `description`은 공백이 아닌 1~500자, `eventType`은 공백이 아닌 1~20자입니다. 최초 상태 PENDING과 createdAt·updatedAt은 서버가 설정합니다. 요청의 status·createdAt·updatedAt은 저장값으로 사용하지 않습니다.

상태 수정 JSON:

```json
{"status":"RESOLVED"}
```

`status`는 기존 DB 길이에 맞춰 공백이 아닌 1~20자 문자열을 받습니다. 상태 변경 시 updatedAt을 서버에서 갱신합니다. 명세에 상세 권한이 없는 상태 수정은 소유자/관리자로 제한했습니다.

이벤트 응답 필드: `id`, `sessionId`, `eventType`, `description`, `status`, `detectedAt`, `createdAt`, `updatedAt`.

### 데이터 관계

[V2 SQL](../../src/main/resources/db/migration/V2__create_location_data.sql)로 `location_data`를 추가했습니다. 적용된 V1은 수정하지 않았습니다. 위치와 위험 이벤트는 같은 세션 UUID를 통해 연결합니다.

```text
users ──< safe_return_sessions ──< location_data
                    └──────────< risk_events ──< risk_event_medias
```

위치 테이블에는 위도·경도 범위 제약과 최신 위치 조회 인덱스를 정의합니다. 이벤트가 특정 위치 행을 직접 참조하는 추가 컬럼은 만들지 않았습니다.

## 이벤트 미디어

| 이름 | 메서드·경로 | 허용 사용자 | 성공 응답 |
|---|---|---|---|
| `uploadEventMedia` | POST `/api/risk-events/{eventId}/medias` | 관리자 | 201, 미디어 객체 |
| `getEventMedias` | GET `/api/risk-events/{eventId}/medias` | 관리자 | 200, 미디어 배열 |
| `deleteEventMedia` | DELETE `/api/risk-events/{eventId}/medias/{mediaId}` | 관리자 | 204, 본문 없음 |

업로드는 multipart/form-data의 `file`과 `mediaType`이 필수입니다.

```sh
curl -X POST "http://localhost:8080/api/risk-events/1/medias" \
  -H "Authorization: Bearer <ADMIN_ID_TOKEN>" \
  -F "file=@./recording.wav" -F "mediaType=AUDIO"
```

`mediaType`은 기존 DB 길이에 맞춰 공백이 아닌 1~20자 문자열을 받습니다. 파일은 비어 있으면 400, 현재 서버 업로드 설정인 10MB를 초과하면 413입니다.

미디어 응답 필드: `id`, `eventId`, `mediaType`, `fileName`, `storageUrl`, `uploadedAt`, `createdAt`, `updatedAt`.

기본 저장소는 비공개 로컬 `data/event-media`이며 `safepath.media.root`로 바꿀 수 있습니다. Git에서 제외하고 정적 파일 URL로 노출하지 않습니다. 무작위 UUID를 파일 키로 사용하며, 원본 파일명은 경로를 제거해 메타데이터에만 저장합니다. `storageUrl`은 이 UUID 저장 키이며 공개 다운로드 주소·서버 절대경로가 아닙니다.

업로드 DB 저장이 롤백되면 파일을 정리합니다. 삭제는 이벤트와 미디어 ID가 함께 일치해야 합니다. 파일 내용을 메모리에 보관한 뒤 파일을 동기적으로 삭제하고, 이어서 DB 메타데이터를 삭제합니다. 저장소 읽기·삭제가 실패하면 500 `MEDIA_STORAGE_ERROR`를 반환하고 DB 레코드는 유지합니다. 파일 삭제 후 DB 트랜잭션이 롤백되면 메모리 사본으로 파일을 복원합니다. `storageUrl`이 NULL이면 파일 작업 없이 메타데이터만 삭제합니다.

파일 복원이나 업로드 롤백 후 정리가 실패하면 식별 정보를 로그에 남깁니다. 복원 사본은 메모리에만 있으므로 프로세스 종료나 복원 자체의 실패까지 DB·파일시스템의 원자성을 보장하지 않습니다. 자동 복구·재시도 기능은 없고, 복원 실패 로그만으로 파일 내용을 되살릴 수는 없습니다. 다중 서버에서는 공유 저장소가 필요합니다.


## 검증과 연결

`RiskApiIntegrationTests`는 이 패키지의 API 7개, 세션 소유권·방향별 보호자 위치 권한, 좌표·시간 검증, 이벤트 상태, 미디어 메타데이터와 실제 파일 처리를 검증합니다. 보호자 등록 행은 테스트 데이터로 준비하며 보호자 API를 호출하지 않습니다.

`RiskRuntimeDatabaseTests`는 실제 HTTP·커밋·롤백, 외래키, 파일 삭제 오류, DB 삽입·삭제 오류, 파일 복원과 업로드 크기 제한을 확인합니다. 테스트마다 별도 스키마에 V1/V2를 적용하고 정리하므로 public 업무 데이터는 변경하지 않습니다. JWT 검증 대역을 사용하므로 실제 Firebase 검증기와 회원·세션 생성 모듈 연결은 필요합니다.

저장소와 서비스 단위 테스트도 포함합니다. 실행 방법은 [공통 API 안내](README.md)를 참고하세요.
