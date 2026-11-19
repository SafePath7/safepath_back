# 음성 프로필 1차: 내부 CRUD와 DB 영속성

## 범위와 제한

기존 V1 `voice_profiles` 테이블에 대한 내부 CRUD만 제공한다. 공개 REST API, Firebase 인가 경계 연결, AI 특징 추출·화자 검증, 음성 업로드·교체·원본 삭제, 회원 탈퇴 연동은 미구현이다. 운영 기능 전체가 완성된 것으로 간주하지 않는다.

특히 `deleteVoiceProfile()`은 **DB 레코드만 삭제**한다. 실제 음성 파일은 남는다. 파일 소유권·삭제·실패 보상 정책을 결합하기 전 공개 삭제 API나 회원 탈퇴 처리에서 사용하지 않는다. `UserService.withdraw()`가 위임하는 `UserOperations.withdraw()`는 `UserRepository.hasFiles()`로 음성 프로필 존재 여부를 확인하고, 프로필이 있으면 파일 삭제 불가로 탈퇴를 보류한다. 메타데이터만 먼저 지워 그 보호 절차를 우회하면 안 된다.

## 구조

팀 backend 규칙에 따라 Entity·Repository·Service는 기능 패키지 바로 아래에 둔다. 기존 다른 도메인 패키지는 이동하지 않는다.

```text
src/main/java/com/safepath/backend/voice/
  VoiceProfile.java
  VoiceProfileRepository.java
  VoiceProfileService.java
  dto/VoiceProfileCommand.java
  dto/VoiceProfileResult.java
  exception/VoiceProfileException.java
src/test/java/com/safepath/backend/voice/
  VoiceProfileServiceTest.java
  VoiceProfileRepositoryTest.java
```

## 내부 호출 계약

| 메서드 | 동작 |
|---|---|
| `createVoiceProfile(Long userId, VoiceProfileCommand command)` | 입력 검증 → 중복 조회 → INSERT/flush → Result |
| `getVoiceProfile(Long userId)` | 해당 사용자 PK 조회 → Result, 없으면 NOT_FOUND |
| `updateVoiceProfile(Long userId, VoiceProfileCommand command)` | 기존 행 조회 → 전체 값 검증 → 갱신/flush → Result |
| `deleteVoiceProfile(Long userId)` | 기존 행 조회 → DB 삭제/flush, 파일 삭제 없음 |

조회는 읽기 전용 트랜잭션, 생성·갱신·삭제는 쓰기 트랜잭션이다. 호출자는 검증된 Firebase UID를 기존 `CurrentUser` 또는 `UserService`로 변환한 **신뢰 가능한 내부 DB 사용자 ID**를 전달해야 한다. 현재 Service 자체는 인증하지 않는다. 향후 클라이언트 userId를 그대로 넘기는 Controller를 만들지 않는다.

`VoiceProfileCommand`는 등록·갱신 공용 내부 입력이며 HTTP 계약이 아니다. `VoiceProfileResult`도 Entity와 분리된 내부 스냅샷이다. 향후 GET 응답에 embedding·audioFileUrl을 노출하는 것으로 확정하지 않는다. 배열은 입력·출력에서 복사하며 Command/Result의 toString은 민감 필드를 생략한다. 이 값 또는 DB 예외의 원문을 로그에 기록하지 않는다.

검증은 양수 userId, 비어 있지 않은 유한 float 배열, 공백 아닌 파일 참조, 유한 volume/pitch, 1~50 Unicode 문자이면서 공백 아닌 modelVersion, 필수 consentedAt으로 제한한다. 임베딩 차원·음량/피치 임계값·파일 URL 형식은 AI/저장소 계약이 없으므로 임의 제한하지 않는다. 검증 실패 전에 기존 Entity를 부분 갱신하지 않는다.

예외는 `VoiceProfileException.reason()`으로 INVALID_INPUT / ALREADY_EXISTS / NOT_FOUND / CONSTRAINT_VIOLATION / DATABASE_ERROR를 구분한다. 기존 전역 예외 처리기는 수정하지 않았으며 HTTP 상태 연결은 후속 작업이다. 생성 중 SQLSTATE 23505는 동시 중복 등록으로 처리한다. FK 등 다른 무결성 오류는 중복으로 오인하지 않는다. 트랜잭션 커밋 시점의 인프라 오류는 Spring 트랜잭션 경계에서도 발생할 수 있다.

## V1 매핑

- PK/FK: `user_id BIGINT` → `users.id`. 별도 ID, User Entity, AudioFile Entity, 테이블/컬럼 추가 없음.
- `speaker_embedding REAL[]`: Hibernate `@JdbcTypeCode(SqlTypes.ARRAY)` + Java `float[]`.
- `audio_file_url TEXT`, `baseline_volume/baseline_pitch DOUBLE PRECISION`, `model_version VARCHAR(50)`.
- `consented_at/created_at/updated_at TIMESTAMP WITHOUT TIME ZONE`: 기존 `UtcTime` 기준 UTC `LocalDateTime`, 마이크로초 정밀도. 내부 DTO는 `Instant`를 사용한다.
- 생성 시 createdAt·updatedAt을 함께 기록한다. update와 JPA PreUpdate에서 updatedAt을 갱신하고 createdAt·userId는 변경하지 않는다. JPA는 시각을 명시적으로 저장하며 기존 DB DEFAULT는 직접 SQL 삽입의 기본값으로 유지된다. 자동 DDL과 V1 변경 없음.
- 직접 지정하는 ID는 `Persistable.isNew()`와 PostPersist/PostLoad로 신규 여부를 구분한다. 신규 save가 merge로 기존 프로필을 덮어쓰지 않게 하고, 동시 등록의 최종 중복 방지는 DB PK가 담당한다. 낙관적 버전 컬럼은 추가하지 않았으며 동시 갱신의 충돌 정책은 후속 합의 대상이다.

## 검증 방법

Java 21과 저장소 Gradle wrapper를 사용한다.

```powershell
.\gradlew.bat compileJava compileTestJava --no-daemon --console=plain
.\gradlew.bat test --tests com.safepath.backend.voice.VoiceProfileServiceTest --no-daemon --console=plain
```

Service 테스트는 Mockito Repository로 CRUD·검증·예외·배열 복사·시간 유지와 변경을 검사한다. 운영 DB나 가짜 AI 운영 데이터를 사용하지 않는다.

Repository 테스트는 H2를 사용하지 않는다. 환경변수가 없으면 명시적으로 건너뛴다. 기존 V1 스키마가 준비된 개인 Local PostgreSQL에만 연결 가능하며 다음 변수는 테스트 프로세스에 안전하게 공급한다. 비밀값을 명령 기록·콘솔에 쓰지 않는다.

| 환경변수 | 목적 |
|---|---|
| `SAFEPATH_VOICE_TEST_DB_URL` | 정확히 `jdbc:postgresql://127.0.0.1:5433/safepath` 또는 localhost 동일 경로만 허용 |
| `SAFEPATH_VOICE_TEST_DB_USERNAME` | 승인된 개인 Local DB 계정 |
| `SAFEPATH_VOICE_TEST_DB_PASSWORD` | 해당 Local 계정 비밀번호 |

```powershell
.\gradlew.bat test --tests com.safepath.backend.voice.VoiceProfileRepositoryTest --no-daemon --console=plain
```

Repository 테스트는 별도 EntityManagerFactory로 voice만 스캔하고 `hibernate.hbm2ddl.auto=validate`를 사용한다. 애플리케이션·Firebase·S3·Flyway는 기동하지 않는다. 각 테스트는 synthetic 사용자와 프로필을 같은 트랜잭션에 넣고 항상 롤백한다. 사용자 ID는 명시적으로 지정하여 기존 identity sequence를 증가시키지 않는다. 기존 행을 수정·삭제하거나 스키마를 만들지 않는다. REAL[] 왕복·실제 타입·전체 필드·PK/FK/NOT NULL·시각 갱신·삭제를 실제 PostgreSQL에서 검사한다.

전체 테스트는 기존 DB 쓰기·임시 테이블·회원 탈퇴 테스트와 미추적 PhoneProtectionTest의 별도 키 의존성을 가진다. RDS 접근, 공유 데이터 변경, 기존 .env 출력 또는 임의 키 생성 없이 안전한 Local 조건을 확인한 후 실행한다. 과거 통과 결과를 이번 실행 결과로 간주하지 않는다.

## 실제 실행 결과

2026-11-19, `feature/voice-profile`에 `origin/develop`의 `f1840b2`를 반영한 상태에서 확인했다.

| 검증 | 상태 | 결과 |
|---|---|---|
| 운영 코드 컴파일 | PASS | `compileJava` 성공 |
| `VoiceProfileServiceTest` | PASS | 50건 통과, 실패·건너뜀 0건 |
| `VoiceProfileRepositoryTest` | PASS | 개인 로컬 PostgreSQL에서 10건 통과, 실패·건너뜀 0건. `REAL[]` 실제 타입·왕복, CRUD, 중복 PK·FK·필수 컬럼 제약, 시각 갱신 확인 |
| 전체 Gradle `test` | FAIL | `compileTestJava`에서 별도 미추적 `PhoneProtectionTest`가 이동 전 패키지의 `PhoneProtection`을 참조해 테스트 실행 전 중단. 현재 클래스는 `com.safepath.backend.user.service.PhoneProtection`에 위치 |

Voice 테스트는 저장소 밖의 임시 Gradle 초기화 스크립트로 테스트 소스 범위를 Voice 패키지로 지정해 실행했다. 전체 테스트 실패를 별도로 기록했으며 `PhoneProtectionTest`와 저장소 빌드 설정은 보존했다.

## 후속 합의

AI 담당자: embedding 차원·품질·모델 버전·동의 갱신 정책·인증·실패 계약.
저장소 담당자: 원본 참조 형식·소유권·교체·영구 삭제·보상 및 재시도.
회원 담당자: 탈퇴와 동시 등록/갱신의 정합성, 원본 삭제 완료 전 메타데이터 보존.
API 담당자: 공개 입력/출력·오류·인가·민감 필드 비노출.

## 2차 API — Notion 확정 본인 조회

`GET /api/voice-profiles/me` (`getVoiceProfile`)는 로그인한 회원의 음성 프로필 등록 여부를 반환한다. 위 1차 범위는 내부 CRUD 구현 당시의 기록이다.

`Authorization: Bearer <Firebase ID token>`이 필요하다. 기존 Firebase 필터가 토큰을 검증하고, Controller는 `CurrentUser.resolve(authentication).id()`로 내부 회원 ID를 구해 `VoiceProfileService.getVoiceProfile()`을 호출한다. 쿼리나 본문의 사용자 ID·Firebase UID는 조회 대상 결정에 사용하지 않는다.

**등록 — HTTP 200**

```json
{
  "registered": true,
  "consentedAt": "2026-11-19T10:00:00Z",
  "createdAt": "2026-11-19T10:00:00Z",
  "updatedAt": "2026-11-19T10:00:00Z"
}
```

**미등록 — HTTP 200**

```json
{"registered": false}
```

| HTTP 상태 | 조건 |
|---|---|
| 200 | 가입 회원의 프로필 등록 여부 확인. 미등록인 경우 날짜 필드 생략 |
| 401 | Firebase 토큰 없음 또는 유효하지 않은 토큰 |
| 404 | Firebase 인증은 성공했으나 가입 회원 없음. 기존 `NOT_FOUND` 정책 적용 |
| 500 | DB 장애 또는 예기치 않은 조회 오류. `ProblemDetail`의 `code`는 `INTERNAL_ERROR` |

공개 응답에서 `userId`, `speakerEmbedding`, `audioFileUrl`, `baselineVolume`, `baselinePitch`, `modelVersion`을 제외한다. 오류 응답에는 SQL과 내부 예외 메시지를 포함하지 않는다.

POST·PUT·DELETE, AI 특징 추출·화자 검증, 음성 파일 업로드·교체·삭제는 후속 작업이다.

### 2차 GET 검증 결과

로컬 Docker PostgreSQL을 사용해 `.\gradlew.bat clean test`를 실행했다.

| 테스트 | 통과 | 실패 | 건너뜀 |
|---|---:|---:|---:|
| VoiceProfileControllerTest | 9 | 0 | 0 |
| VoiceProfileServiceTest | 50 | 0 | 0 |
| VoiceProfileRepositoryTest | 10 | 0 | 0 |
| 전체 Gradle 테스트 | 257 | 0 | 0 |

Controller 테스트는 실제 Spring Security 필터 체인과 `CurrentUser`를 사용한다. Firebase 토큰 검증, 회원 조회, Voice Service는 모의 처리한다. PostgreSQL 영속성은 별도 Repository 테스트에서 검증했다.

### 개발 진행 상황

- 완료 · VoiceProfile Entity, Repository, Service 및 내부 CRUD
- 완료 · Firebase 인증 기반 `GET /api/voice-profiles/me`
- 완료 · 로컬 Postman TEST 1~6 통과. 토큰 없음·잘못된 토큰은 401, 프로필 미등록·등록은 각각 200, 로컬 미가입은 404를 확인했다. 다른 회원의 ID를 쿼리에 넣어도 인증된 본인의 등록 상태만 반환했다.
- 후속 작업 · POST 생성, PUT 갱신, DELETE 삭제 REST API
- 후속 작업 · AI 음성 특징 추출, 음성 파일 저장소 연동, Android 통합 테스트

후속 REST API와 AI 연동 완료 후 `develop` 대상 최종 PR 하나로 정리할 예정이다.
