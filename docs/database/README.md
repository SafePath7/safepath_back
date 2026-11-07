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

`safe_return_sessions.id`는 UUID입니다. 안전귀가 시작 시 백엔드에서 `UUID.randomUUID()`로 생성해 메모리에서 관리하고, 위험 상황을 DB에 저장할 때 같은 ID를 사용하도록 설계했습니다. 이 동작은 아직 구현되지 않았고 DB 기본 UUID 생성 함수도 없습니다. 위험 이벤트의 `session_id`도 UUID입니다.

보호자 등록은 사용자·보호자 쌍의 중복과 자기 자신 등록을 제한하며 세 조회 권한은 각각 0 또는 1만 허용합니다. `voice_profiles.speaker_embedding`은 `REAL[]`입니다. 상태값의 허용 범위와 FK 삭제 정책은 추가 검토가 필요합니다. `updated_at`의 기본값은 삽입 시에만 적용되며 자동 갱신하지 않습니다.

## Flyway 변경 관리

마이그레이션은 `src/main/resources/db/migration/`에 `V1__create_tables.sql` 같은 버전 파일로 저장합니다. Flyway는 적용 이력을 `flyway_schema_history`에 기록합니다. V1은 기존 로컬 DB에서 적용·검증됐으며, 이미 적용된 파일을 수정하면 체크섬 불일치가 생길 수 있습니다.

스키마를 변경할 때는 V1을 수정하지 않고 `V2__설명.sql`, `V3__설명.sql`처럼 새 버전을 작성해 PR에서 검토합니다. 적용 순서와 기존 데이터에 미치는 영향을 함께 확인합니다. 로컬은 `local` 프로필에서 자동 적용하고, 공용 RDS는 자동 적용을 끈 상태에서 담당자가 변경을 검토한 후 수동으로 적용할 계획입니다. 공용 DB에 적용할 구체적인 절차는 사전에 합의해야 합니다.

로컬 DB는 개발자별 Docker named volume에 보관됩니다. AWS RDS는 팀이 공유할 원격 DB로 계획되어 있으며 로컬 컨테이너와 데이터가 공유되지 않습니다. 접속 설정은 [배포 문서](../deployment/README.md)를 참고합니다.
