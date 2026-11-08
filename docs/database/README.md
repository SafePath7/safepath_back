# 데이터베이스와 마이그레이션

로컬 개발에는 PostgreSQL 18.3을 사용합니다. [V1 SQL](../../src/main/resources/db/migration/V1__create_tables.sql)은 최종 ERD를 바탕으로 다음 7개 테이블을 생성합니다.

| 테이블 | 역할 | PK | FK |
|---|---|---|---|
| `users` | 사용자 정보 | `id` | — |
| `guardian_registrations` | 사용자와 보호자의 등록 및 조회 권한 | `id` | `user_id`, `guardian_id` → `users.id` |
| `voice_profiles` | 사용자별 음성 프로필 | `user_id` | `user_id` → `users.id` |
| `safety_facilities` | 안전시설 위치와 유형 | `id` | — |
| `safe_return_sessions` | 안전귀가 세션 | `id` | `user_id` → `users.id` |
| `risk_events` | 세션에서 감지된 위험 이벤트 | `id` | `session_id` → `safe_return_sessions.id` |
| `risk_event_medias` | 위험 이벤트의 미디어 정보 | `id` | `event_id` → `risk_events.id` |

[V2 SQL](../../src/main/resources/db/migration/V2__create_location_data.sql)은 `location_data` 테이블을 추가합니다. PK는 BIGINT `id`, `session_id`는 `safe_return_sessions.id`를 참조하는 UUID입니다. 위도·경도 범위 제약과 `(session_id, recorded_at DESC, id DESC)` 최신 위치 조회 인덱스를 정의합니다. 위치와 위험 이벤트는 같은 세션을 통해 연결되며 현재 전체 스키마는 8개 테이블입니다.

`safe_return_sessions.id`는 UUID입니다. 안전귀가 시작 시 백엔드에서 `UUID.randomUUID()`로 생성해 메모리에서 관리하고, 위험 상황을 DB에 저장할 때 같은 ID를 사용하도록 설계했습니다. 이 동작은 아직 구현되지 않았고 DB 기본 UUID 생성 함수도 없습니다. 위험 이벤트의 `session_id`도 UUID입니다.

현재 위치·위험 이벤트 API는 FK가 있는 저장소를 사용하므로 해당 UUID의 세션 행이 DB에 먼저 있어야 합니다. 메모리 세션을 언제 DB에 저장할지는 세션 담당자와 연결해야 합니다. 새 API가 작성하는 시각과 기존 TIMESTAMP 컬럼의 API 해석은 UTC를 기준으로 합니다.

## 첨부 테이블과 API 매핑

사용자가 제공한 보호자 연결·위험 이벤트·이벤트 미디어 테이블은 기존 V1의 해당 세 테이블과 컬럼명·자료형·NULL 허용 여부가 일치합니다. `id`는 BIGINT IDENTITY이고, `guardian_registrations.user_id`와 `guardian_id`는 자동 생성 값이 아닌 기존 `users.id` 외래키입니다. 이벤트는 UUID 세션을, 미디어는 BIGINT 이벤트 ID를 참조합니다.

권한 세 컬럼은 SMALLINT 0/1을 유지하고 API에서 Boolean으로 변환합니다. `accepted_at`, `file_name`, `storage_url`, `uploaded_at`의 NULL을 API에서도 유지하며, `storage_url`이 NULL인 미디어 삭제는 파일 저장소를 호출하지 않습니다. 정상 업로드에서는 파일을 저장한 후 경로와 업로드 시각을 기록합니다.

미디어 삭제는 파일 내용을 메모리에 보관하고 파일을 동기적으로 삭제한 뒤 DB 레코드를 삭제합니다. 저장소 읽기·삭제 오류가 발생하면 DB 레코드를 유지하고 500을 반환합니다. 이후 DB 삭제가 롤백되면 메모리 사본으로 파일을 복원합니다. 사본을 영구 저장하지 않으므로 프로세스 종료나 복원 자체의 실패까지 DB·파일시스템의 원자성을 보장하지 않으며, 자동 복구·재시도도 제공하지 않습니다. 업로드 후 DB 저장이 롤백되는 경우에는 생성한 파일을 정리합니다.

세부 요청/응답 매핑은 [위치·위험 이벤트 API 문서](../api/risk-events.md)를 참고하세요. 보호자 등록 API는 별도 기능 브랜치에서 구현합니다.

## DB와 API 검증 방식

`RiskApiIntegrationTests`는 별도 테스트 스키마에 V1/V2를 적용한 뒤 컬럼 자료형·길이·NULL·IDENTITY와 외래키 정의를 조회합니다. 요청은 해당 스키마에서 MockMvc로 처리하고 테스트마다 롤백합니다. 원본 테이블 복제가 아닌 실제 마이그레이션을 사용하므로 외래키도 적용됩니다. PENDING·NULL·시각 기본값과 보호자 위치 권한도 확인합니다.

`RiskRuntimeDatabaseTests`는 동일한 Docker PostgreSQL 안에 `api_audit_<UUID>` 형태의 독립 스키마를 생성하고 Flyway V1/V2를 적용합니다. 애플리케이션과 검사 쿼리는 해당 스키마만 사용하며, `127.0.0.1`의 임의 포트에 실제 HTTP 요청을 보내 DB 커밋·롤백과 외래키·권한 제약을 검사합니다. 미디어는 임시 디렉터리를 사용합니다. 저장소 오류와 DB 삽입·삭제 오류를 주입해 오류 응답, DB 레코드 보존, 업로드 파일 정리, 삭제 파일 복원을 확인하고 실제 서블릿의 업로드 크기 제한도 검사합니다. 테스트 종료 시 생성한 스키마와 미디어 디렉터리를 제거합니다.

두 통합 테스트 모두 public 스키마의 업무 행을 수정하지 않습니다. Firebase ID 토큰 검증은 테스트 대역으로 대체합니다. 실제 `FirebaseIdTokenVerifier` 구현체는 아직 연결되지 않았으므로 실제 Firebase 로그인·토큰 검증까지 확인하는 테스트는 아닙니다.

## 기타 스키마 사항

보호자 등록은 사용자·보호자 쌍의 중복과 자기 자신 등록을 제한하며 세 조회 권한은 각각 0 또는 1만 허용합니다. `voice_profiles.speaker_embedding`은 `REAL[]`입니다. 상태값의 허용 범위와 FK 삭제 정책은 추가 검토가 필요합니다. `updated_at`의 기본값은 삽입 시에만 적용되며 자동 갱신하지 않습니다.

## Flyway 변경 관리

마이그레이션은 `src/main/resources/db/migration/`에 `V1__create_tables.sql` 같은 버전 파일로 저장합니다. Flyway는 적용 이력을 `flyway_schema_history`에 기록합니다. V1은 기존 로컬 DB에서 적용·검증됐으며, 이미 적용된 파일을 수정하면 체크섬 불일치가 생길 수 있습니다.

스키마를 변경할 때는 V1을 수정하지 않고 `V2__설명.sql`, `V3__설명.sql`처럼 새 버전을 작성해 PR에서 검토합니다. 적용 순서와 기존 데이터에 미치는 영향을 함께 확인합니다. 로컬은 `local` 프로필에서 자동 적용하고, 공용 RDS는 자동 적용을 끈 상태에서 담당자가 변경을 검토한 후 수동으로 적용할 계획입니다. 공용 DB에 적용할 구체적인 절차는 사전에 합의해야 합니다.

로컬 DB는 개발자별 Docker named volume에 보관됩니다. AWS RDS는 팀이 공유할 원격 DB로 계획되어 있으며 로컬 컨테이너와 데이터가 공유되지 않습니다. 접속 설정은 [배포 문서](../deployment/README.md)를 참고합니다.
