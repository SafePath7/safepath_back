# SafePath 안전시설 모듈

[日本語](README.ja.md)

## 1. 범위와 목적

이 문서는 `com.safepath.backend.safety` 패키지와 이에 직접 연결된 Flyway 마이그레이션 및 테스트만 설명한다. 안전시설 모듈은 지도 화면에 시설을 표시하고, 관리자 CRUD와 공공데이터 동기화를 통해 `safety_facilities`를 관리한다.

```text
공개 지도 요청 ──> SafetyFacilityController ──> SafetyFacilityService ──> Repository
관리자 요청 ─────> SafetyFacilityAdminController ─> AdminService ───────> Repository
스케줄러 ───────> 공급원별 SyncService ────────> SafetyFacilitySyncService ─> Repository
```

## 2. 패키지와 책임

| 위치 | 책임 |
| --- | --- |
| `controller` | 공개 지도 조회 HTTP API |
| `admin/controller`, `admin/service`, `admin/dto` | 관리자용 시설 조회·생성·수정·삭제 |
| `service` | 지도 조회 정책 및 공통 upsert |
| `entity`, `repository`, `dto` | 영속 모델, 조회, API/동기화 데이터 모델 |
| `facility/<source>` | CCTV, 비상벨, 경찰서, 안전지킴이집, 보안등 외부 API 클라이언트·변환·스케줄 |
| `facility/exception` | 외부 API 요청·재시도 소진 오류 |

## 3. 데이터 모델과 마이그레이션

테이블은 `safety_facilities`이며 JPA 엔티티는 `SafetyFacility`이다.

| 컬럼 | 타입/제약 | 의미 |
| --- | --- | --- |
| `id` | BIGINT PK | 내부 식별자 |
| `latitude`, `longitude` | DOUBLE PRECISION, NOT NULL | WGS84 위도·경도 |
| `address` | VARCHAR(255), NOT NULL | 표시 주소 |
| `facility_type` | VARCHAR(50), NOT NULL | `CCTV`, `POLICE` 등 시설 분류 |
| `source` | VARCHAR(30), nullable | 공급원. 예: `CCTV`, `ADMIN` |
| `external_id` | VARCHAR(255), nullable | 공급원 안에서의 외부 식별자 |
| `created_at`, `updated_at` | TIMESTAMP, NOT NULL | 생성·최근 갱신 시각 |

- `V1__create_tables.sql`은 기본 테이블을 생성한다.
- `V2__add_safety_facility_source_and_external_id.sql`은 동기화 식별용 `source`, `external_id`를 추가한다. 이미 존재하는 컬럼은 `IF NOT EXISTS`로 건너뛰므로, 이력이 V1에 머물러 있으나 컬럼이 선반영된 환경도 V2 이력으로 정렬할 수 있다. 이미 적용된 V1은 수정하지 않는다.
- 현재 기본 설정은 `spring.jpa.hibernate.ddl-auto=validate`이고 `spring.flyway.enabled=false`이다. 따라서 실제 환경에서 V2를 적용하려면 Flyway를 활성화한 배포 절차 또는 승인된 DB 마이그레이션 절차가 필요하다. 애플리케이션이 자동으로 스키마를 고쳐 주지 않는다.

`SafetyFacility.create(source, externalId, latitude, longitude, address, facilityType)`은 새 엔티티와 타임스탬프를 만들고, `update(...)`는 좌표·주소·유형과 `updatedAt`만 바꾼다. 공급원과 외부 ID는 갱신 시 바꾸지 않는다.

## 4. 공개 지도 API

`GET /api/safety-facilities`는 SecurityConfig에서 인증 없이 허용된다. 요청 파라미터는 모두 필수다.

| 파라미터 | 범위 | 설명 |
| --- | --- | --- |
| `zoom` | 1~14 | 카카오 지도 레벨 |
| `southWestLat`, `northEastLat` | -90~90 | 조회 영역의 남서/북동 위도 |
| `southWestLng`, `northEastLng` | -180~180 | 조회 영역의 남서/북동 경도 |

`southWestLat <= northEastLat` 및 `southWestLng <= northEastLng`이어야 하며, 순서가 뒤집히면 400을 반환한다.

```http
GET /api/safety-facilities?zoom=3&southWestLat=37.50&southWestLng=126.90&northEastLat=37.60&northEastLng=127.10
```

### 응답과 클러스터 정책

응답은 `SafetyFacilityMapResponse(mode, totalCount, clusters, facilities)`다.

- `zoom <= 3`: `mode`는 `MARKER`; 시설 전체를 `facilities`에 ID 오름차순으로 반환하고 `clusters`는 빈 배열이다.
- `zoom >= 4`: `mode`는 `CLUSTER`; 시설 수는 `totalCount`에, 격자별 평균 좌표와 개수는 `clusters`에 반환하며 `facilities`는 빈 배열이다.
- 격자 크기: `10.24 / 2^(14 - zoom)`도. SQL은 `FLOOR(latitude / gridSize)`, `FLOOR(longitude / gridSize)`로 묶고 각 그룹의 평균 좌표와 개수를 계산한다.

마커 항목은 `id`, `latitude`, `longitude`, `address`, `facilityType`이고, 클러스터 항목은 `latitude`, `longitude`, `count`다. 공개 응답에는 `source`, `externalId`를 노출하지 않는다.

## 5. 관리자 API

기본 경로는 `/api/admin/safety-facilities`다. 모든 요청은 인증이 필요하고, 서비스가 `CurrentUser.Actor.requireAdmin()`으로 관리자 권한을 다시 확인한다. 일반 사용자와 익명 사용자는 관리할 수 없다.

| 메서드/경로 | 메서드 | 설명 | 성공 응답 |
| --- | --- | --- | --- |
| `GET /api/admin/safety-facilities?page=0&size=50` | `list` | ID 내림차순 페이지 조회 | 200 |
| `GET /api/admin/safety-facilities/{facilityId}` | `get` | 단건 조회 | 200 |
| `POST /api/admin/safety-facilities` | `create` | 수동 시설 생성 | 201 |
| `PATCH /api/admin/safety-facilities/{facilityId}` | `update` | 좌표·주소·유형 수정 | 200 |
| `DELETE /api/admin/safety-facilities/{facilityId}` | `delete` | 단건 삭제 | 204 |

`page`는 0 이상, `size`는 1~100이다. `facilityId`는 양수여야 한다. 요청 본문은 다음과 같고, 위도/경도 범위와 문자열 길이를 Bean Validation으로 검증한다.

```json
{
  "latitude": 37.5665,
  "longitude": 126.9780,
  "address": "서울특별시 중구 세종대로 110",
  "facilityType": "CCTV"
}
```

생성된 수동 시설의 `source`는 `ADMIN`, `externalId`는 서버가 만든 UUID다. 관리자 응답에는 `id`, 좌표, 주소, 유형, `source`, `createdAt`, `updatedAt`이 포함된다. 존재하지 않는 ID는 공통 예외 처리로 404가 된다.

## 6. 서비스·Repository 메서드

### `SafetyFacilityService`

| 메서드 | 역할 |
| --- | --- |
| `findInBounds(...)` | 범위 순서를 검증하고 지도 레벨에 따라 마커 전체 조회 또는 클러스터 조회를 선택한다. 읽기 전용 트랜잭션이다. |

### `SafetyFacilitySyncService`

| 메서드 | 역할 |
| --- | --- |
| `hasFacilities(source)` | 특정 공급원 데이터가 이미 존재하는지 확인한다. 초기 자동 동기화 건너뛰기 판단에 쓴다. |
| `sync(item)` | `(source, externalId)` 단건 upsert. 있으면 업데이트, 없으면 저장한다. |
| `syncAll(items)` | 공급원별로 묶어 기존 시설을 한 번에 조회한 뒤 생성 목록만 `saveAll`하고 기존 엔티티는 변경 감지로 갱신한다. 생성/수정 건수를 `SyncResult`로 돌려준다. |

`SafetyFacilityRepository`의 주요 메서드는 `existsBySource`, `findBySourceAndExternalId`, `findBySourceAndExternalIdIn`, 범위 마커 조회, 범위 수 조회, 그리고 native SQL 기반 `findClustersInBounds`다. 동기화의 식별 기준은 현재 DB 제약이 아닌 `(source, externalId)` 조회 규약이므로, 공급원별 `externalId`의 안정성을 유지해야 한다.

## 7. 외부 데이터 동기화

공급원별 모듈은 API 응답을 `SafetyFacilityImportItem(source, externalId, latitude, longitude, address, facilityType)`으로 변환한 뒤 공통 동기화 서비스에 전달한다.

| 공급원 패키지 | `source` / 유형 | 동기화 메서드 | 환경변수 |
| --- | --- | --- | --- |
| `facility/cctv` | `CCTV` / `CCTV` | `syncAll()`, `syncAll(maxPages)`, `syncAll(startPage, maxPages)` | `SAFEPATH_CCTV_API_KEY` |
| `facility/emergencycallbox` | `EMERGENCY_CALL_BOX` / 공급원 변환값 | 위와 동일 | `SAFEPATH_EMERGENCY_CALL_BOX_API_KEY` |
| `facility/policestation` | `POLICE_STATION` / `POLICE` | `syncAll(maxPages)` | `SAFEPATH_POLICE_STATION_API_KEY` |
| `facility/safetyguardianstore` | `SAFETY_GUARDIAN_STORE` / `SAFETY_GUARDIAN_STORE` | `syncAll(maxPages)` | `SAFEPATH_SAFETY_GUARDIAN_STORE_API_KEY` |
| `facility/securitylight` | `SECURITY_LIGHT` / 공급원 변환값 | `syncAll()` | `SAFEPATH_SECURITY_LIGHT_API_KEY` |

모든 API 클라이언트의 `fetch(pageNo, numOfRows)`는 키가 비어 있으면 즉시 실패한다. 동기화 서비스는 빈/유효하지 않은 좌표를 건너뛰고, 공급원 API의 페이지·응답 상태를 검증한다. 경찰서 좌표는 Web Mercator에서 WGS84로 변환한다.

### 재시도와 실패 처리

`SafetyFacilityApiRetryExecutor.execute(pageNo, supplier)`는 재시도 가능한 HTTP/네트워크 오류에 지수 백오프를 적용한다. 재시도 불가능한 HTTP 오류는 즉시 전파하고, 시도 횟수를 모두 소진하면 `SafetyFacilityApiRetryExhaustedException`을 던진다. CCTV는 별도 `CctvApiRetryExecutor`를 사용한다. 동기화 서비스는 일시 오류가 모두 소진된 페이지를 설정된 지연 후 같은 페이지에서 다시 시도하도록 구현되어 있다.

### 스케줄러

각 공급원 `*SyncScheduler.sync()`는 `@Scheduled`로 동작한다. 기본 최초 지연은 0ms, 기본 주기는 `7,776,000,000ms`(약 90일)다. 첫 실행에서 해당 `source` 데이터가 이미 있으면 초기 전체 동기화를 건너뛴다.

| 공급원 | 주요 설정 키 |
| --- | --- |
| CCTV | `safepath.cctv.sync.start-page`, `max-pages`, `initial-delay-ms`, `interval-ms`, `retry.*`, `request-interval-ms`, `failed-page-retry-delay-ms` |
| 비상벨 | `safepath.emergency-call-box.sync.start-page`, `max-pages`, `initial-delay-ms`, `interval-ms` |
| 경찰서 | `safepath.police-station.sync.max-pages`, `initial-delay-ms`, `interval-ms` |
| 안전지킴이집 | `safepath.safety-guardian-store.sync.max-pages`, `initial-delay-ms`, `interval-ms` |
| 보안등 | `safepath.security-light.sync.initial-delay-ms`, `interval-ms` |

`max-pages=0`은 공급원 응답의 마지막 페이지까지 처리한다. API 키와 실제 비밀값은 문서·소스 저장소에 넣지 않는다.

## 8. 테스트

### 전체 또는 안전시설 테스트 실행

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "com.safepath.backend.safety.*"
.\gradlew.bat test --tests "com.safepath.backend.safety.service.SafetyFacilityServiceTest"
.\gradlew.bat test --tests "com.safepath.backend.safety.admin.SafetyFacilityAdminApiTests"
```

테스트는 다음을 검증한다.

| 테스트 영역 | 검증 내용 |
| --- | --- |
| `SafetyFacilityApiTests` | 비인증 공개 조회, 가까운 레벨의 마커, 먼 레벨의 클러스터, 잘못된 범위 400 |
| `SafetyFacilityAdminApiTests` | 관리자 CRUD와 일반/익명 사용자의 접근 차단 |
| `SafetyFacilityServiceTest` | 줌 경계, SQL 조회 선택, 범위 순서 검증 |
| `SafetyFacilitySyncServiceTest`, `SafetyFacilityTest` | source/externalId 기준 생성·수정·배치 집계·타임스탬프 |
| 공급원별 `*SyncServiceTest` | 페이지 순회, 잘못된 좌표 제외, 시작/최대 페이지, 실패 페이지 재시도 |
| `*SyncSchedulerTest`, `*RetryExecutorTest` | 초기 데이터 존재 시 건너뛰기, 예약 호출, 재시도 정책 |

통합/API 테스트는 프로젝트 테스트 프로필의 DB·보안 구성을 사용한다. 로컬에서 Flyway를 활성화해 검증할 경우 빈 DB에 V1, V2가 순서대로 적용되는지 확인하고, 이미 적용된 마이그레이션 파일은 수정하지 않는다.

## 9. 변경 시 체크리스트

1. 새 공급원은 고유한 `source`와 안정적인 `externalId`를 정하고, API 클라이언트·응답 DTO·변환·스케줄러·단위 테스트를 함께 추가한다.
2. 엔티티 컬럼을 추가하면 새 Flyway 버전 파일도 추가한다. 적용 이력이 있는 파일은 수정하지 않는다.
3. 공개 API 응답 필드를 바꾸면 지도 클라이언트와 보안의 `permitAll` 경로 영향을 함께 확인한다.
4. 관리자 API는 `requireAdmin()` 검사를 유지하고, 공공 API 키는 환경변수로만 주입한다.
5. 대량 데이터 공급원은 지도 저레벨에서 전체 마커를 반환한다는 점과 클러스터 SQL 성능을 함께 점검한다.
