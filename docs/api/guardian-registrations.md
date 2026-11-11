# 보호자 등록·권한 API

`feature/guardian-registration-api`는 `develop` 기준의 보호자 API 7개를 구현합니다. 위험 이벤트 API를 포함하지 않습니다. 기존 V1의 `guardian_registrations`를 사용하며 DB 테이블·컬럼과 요청·응답 형식을 변경하지 않았습니다.

`id`는 BIGINT 자동 증가, `user_id`와 `guardian_id`는 기존 회원 외래키입니다. 권한 세 컬럼은 SMALLINT 0/1, 수락 전 `accepted_at`은 NULL이며 상태 기본값은 PENDING입니다.

## 공통 전제

- 기본 주소는 `http://localhost:8080`입니다. 운영에서는 HTTPS를 사용합니다.
- 모든 요청은 `Authorization: Bearer <Firebase ID token>`을 사용합니다.
- 실제 토큰 검증은 공통 담당자가 제공하는 `FirebaseIdTokenVerifier` Bean이 담당하며, 현재 실제 구현체는 연결되지 않았습니다. 이번 코드는 이 검증기를 호출하는 필터와 UID·관리자 권한 연결을 구현합니다. JWT 서명·만료·issuer·audience 검증 로직은 새로 만들지 않았습니다.
- 검증기가 없거나 검증에 실패하면 401입니다. 요청 본문의 `userId`나 임의 UID·관리자 헤더를 인증에 사용하지 않습니다.
- 검증된 UID의 `users` 행이 미리 있어야 합니다. 미가입 UID는 404입니다. 회원 가입 API는 이번 명세에 없습니다.
- 시각 입력은 오프셋이 있는 ISO 8601 문자열입니다. 예: `2026-11-08T10:00:00+09:00`. 새 API는 기존 TIMESTAMP 컬럼을 UTC로 저장·해석하고 응답을 UTC `Z` 형식으로 반환합니다.


## 보호자 등록 및 권한

| 이름 | 메서드·경로 | 허용 사용자 | 성공 응답 |
|---|---|---|---|
| `requestRegistration` | POST `/api/guardian-registrations` | 요청자 본인 | 201, 등록 객체 |
| `acceptRegistration` | PATCH `/api/guardian-registrations/{registrationId}/accept` | 초대받은 보호자 본인 | 200, 등록 객체 |
| `rejectRegistration` | PATCH `/api/guardian-registrations/{registrationId}/reject` | 초대받은 보호자 본인 | 200, 등록 객체 |
| `revokeRegistration` | PATCH `/api/guardian-registrations/{registrationId}/revoke` | 등록 당사자 또는 관리자 | 200, 등록 객체 |
| `suspendRegistration` | PATCH `/api/guardian-registrations/{registrationId}/suspend` | 관리자 | 200, 등록 객체 |
| `updatePermission` | PATCH `/api/guardian-registrations/{registrationId}/permissions` | 등록 요청자 또는 관리자 | 200, 등록 객체 |
| `checkPermission` | GET `/api/guardian-registrations/{registrationId}/permissions/check?permissionType=LOCATION` | 등록 당사자 또는 관리자 | 200, `{"allowed":true}` |

등록 요청 JSON:

```json
{"guardianId":2}
```

`guardianId`는 양의 정수이며 존재하는 회원이어야 합니다. 요청자의 ID는 인증된 UID로 서버가 조회합니다. 자기 자신 등록은 400, 기존 UNIQUE 제약에 따라 동일 쌍의 중복 요청은 409입니다. 최초 상태는 PENDING, 조회 권한 세 개는 false이며 요청 시각은 서버에서 설정합니다.

등록 객체 예시:

```json
{
  "id":1,
  "userId":1,
  "guardianId":2,
  "status":"PENDING",
  "requestedAt":"2026-11-08T01:00:00Z",
  "acceptedAt":null,
  "canViewLocation":false,
  "canViewStatus":false,
  "canViewPersonalInfo":false,
  "createdAt":"2026-11-08T01:00:00Z",
  "updatedAt":"2026-11-08T01:00:00Z"
}
```

수락·거절·해제·중지는 본문 없이 호출합니다. 수락하면 ACCEPTED와 수락 시각을 기록합니다. 상태 수정 중에는 행을 잠가 동시 변경이 덮어쓰지 않게 합니다.

| 현재 상태 | 가능한 처리 |
|---|---|
| PENDING | 보호자 수락·거절, 당사자/관리자 해제, 관리자 중지 |
| ACCEPTED | 요청자/관리자 권한 수정, 당사자/관리자 해제, 관리자 중지 |
| SUSPENDED | 당사자/관리자 해제 |

V1의 사용자·보호자 쌍 UNIQUE 제약을 유지하며 중복 요청은 기존 등록을 변경하지 않고 409로 반환합니다.

권한 수정 JSON(세 값 모두 필수):

```json
{"canViewLocation":true,"canViewStatus":true,"canViewPersonalInfo":false}
```

DB에는 SMALLINT 1/0으로 저장합니다. 보호자가 자기 권한을 스스로 높일 수 없습니다. 권한 수정·확인에 대한 상세 접근 규칙이 명세에 없어 위 표의 정책으로 구체화했습니다. `permissionType`은 LOCATION, STATUS, PERSONAL_INFO만 허용합니다. 상태가 ACCEPTED가 아니면 비트 값과 관계없이 `allowed=false`입니다.


## 검증과 연결

`GuardianApiIntegrationTests`는 이 패키지의 API가 7개인지와 요청자·보호자·관리자 접근, 상태 변경, 권한 1/0, 기본값·컬럼·외래키 정의를 검증합니다. `GuardianRuntimeDatabaseTests`는 실제 HTTP로 등록·수락·권한 수정·해제를 커밋하고 외래키 위반과 401/403 처리를 확인합니다.

Docker PostgreSQL에 테스트별 별도 스키마를 만들고 V1을 적용해 테스트한 뒤 제거합니다. public 업무 데이터는 수정하지 않습니다. 테스트의 Firebase 검증기는 대역이므로 실제 로그인 연동은 공통 담당 모듈 연결이 필요합니다. 실행 방법은 [공통 API 안내](README.md)를 참고하세요.

이 브랜치로 앱을 직접 실행할 때는 V1과 일치하는 DB 또는 별도 개발 스키마를 사용합니다. 이미 V2가 적용된 public 스키마는 이 브랜치에 없는 마이그레이션 때문에 Flyway 검증에 실패할 수 있습니다. 기존 DB의 이력을 삭제하거나 검증을 끄지 않습니다. 위 테스트의 스키마 격리는 앱의 기본 실행 설정을 바꾸지 않습니다.
