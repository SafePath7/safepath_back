# 데이터베이스와 JPA 규칙

## PostgreSQL 네이밍과 구조 보호

- 테이블은 복수형 `snake_case`(예: `safety_facilities`), 컬럼은 `snake_case`(예: `created_at`)를 쓴다.
- PK는 `id`, FK는 `{대상 또는 역할}_id`(예: `user_id`, `guardian_id`)를 쓴다. PostgreSQL 예약어를 이름으로 쓰지 않는다.
- 기존 테이블·컬럼·관계·제약조건을 임의로 변경하지 않는다. 스키마 변경은 영향 분석과 사용자 승인 뒤 진행하며 애플리케이션 호환성을 유지한다.

## Flyway

- 이미 적용된 마이그레이션 파일·이력을 수정·삭제하지 않는다.
- 신규 마이그레이션 전 기존 정책과 파일·버전 충돌을 확인한다.

## JPA Entity 매핑

1. JPA Entity와 실제 PostgreSQL 스키마의 일치 여부를 확인한다.
2. 기존 `@Table`, `@Column`, `@JoinColumn`과 `@OneToMany`, `@ManyToOne`, `@OneToOne` 매핑을 임의로 변경하지 않는다.
3. DB 변경 시 테이블·컬럼·관계·제약조건 영향을 분석하고 승인 뒤 Flyway 마이그레이션을 검토한다.
4. `ddl-auto=validate` 환경에서 JPA가 스키마를 자동 생성·변경하도록 설정하지 않는다.
5. 기존 Entity–Repository 관계를 유지하고, 신규 Entity는 기존 매핑 방식과 네이밍을 따른다.
6. Entity 내부 로직 변경도 실제 영향 범위를 확인한 뒤 진행한다.
