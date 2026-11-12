# SafePath 백엔드

[한국어](README.md) | [日本語](README.ja.md)

SafePath는 안전귀가를 지원하기 위한 캡스톤 프로젝트입니다. 이 저장소는 Spring Boot 기반 백엔드 API 서버와 데이터베이스 스키마, 로컬 개발 환경을 관리합니다.

## 기술 스택

- Java 21, Spring Boot 4.1.2, Gradle Wrapper 9.8.2
- Spring Web MVC, Security, Validation, Data JPA, Lombok
- Firebase Admin SDK (Firebase ID 토큰 검증)
- PostgreSQL 18.3, Docker Compose, Flyway 12.4.0

## 현재 상태

Spring Boot 기본 골격, DB 프로필, Docker PostgreSQL과 7개 테이블을 정의한 Flyway V1이 준비되어 있습니다. Firebase ID 토큰을 검증하고 DB에서 가입 여부를 확인하는 로그인 API와 관련 테스트가 있습니다.

회원가입 처리와 안전귀가 및 위험 감지 기능은 아직 구현되지 않았습니다.

## 로컬 개발 시작

1. JDK 21과 Docker Desktop을 준비하고 Docker 엔진을 실행합니다.
2. 프로젝트 루트에서 `.env.example`을 `.env`로 복사하고 `POSTGRES_PASSWORD`를 로컬 전용 값으로 변경합니다. `POSTGRES_DB=safepath`를 유지합니다. Firebase 서비스 계정 JSON 파일을 안전한 위치에 준비하고, `.env`에 `GOOGLE_APPLICATION_CREDENTIALS=/JSON/파일의/절대경로`를 추가합니다.
3. `docker compose up -d`로 DB를 시작하고 `docker compose ps`에서 healthy 상태를 확인합니다.
4. IntelliJ 실행 설정에서 `local` 프로필을 활성화하고 프로젝트 루트를 작업 디렉터리로 지정합니다. 로컬 설정은 프로젝트 루트의 `.env`를 읽습니다.
5. `BackendApplication`을 실행합니다. 로컬 DB는 `localhost:5433/safepath`이며 Flyway 자동 실행과 JPA `ddl-auto=validate`가 적용됩니다. 앱의 기본 주소는 `http://localhost:8080`입니다.

서버 시작 시 `FirebaseConfig`가 지정된 JSON 파일을 읽어 Firebase Admin SDK를 초기화합니다. 파일 경로가 잘못되었거나 파일을 읽을 수 없으면 서버 시작에 실패할 수 있습니다. 서비스 계정 JSON과 `.env`는 저장소에 올리지 않습니다.

macOS/Linux 터미널에서는 신뢰할 수 있는 `.env`에 한해 다음과 같이 실행할 수 있습니다.

```sh
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=local sh gradlew bootRun
```

동일한 환경에서 `SPRING_PROFILES_ACTIVE=local sh gradlew test`로 컨텍스트 테스트를 실행합니다. Windows에서는 같은 환경변수와 `local` 프로필을 설정한 뒤 `gradlew.bat bootRun`, `gradlew.bat test`를 사용할 수 있습니다. `gradlew.bat build` 또는 `sh gradlew build`는 테스트 후 빌드하며 결과물은 `build/libs`에 생성됩니다. 빌드 산출물 정리에는 `gradlew.bat clean` 또는 `sh gradlew clean`을 사용합니다. 기본 프로필에는 DB 접속 정보가 없어 컨텍스트 테스트가 실패할 수 있습니다.

Windows PowerShell 명령과 환경변수 설정은 [Docker 문서](docs/docker/README.md#windows-powershell)를 참고하세요. 로컬 Flyway는 Spring Boot가 실행하므로 팀원이 Flyway CLI를 설치할 필요는 없습니다. 공용 RDS 마이그레이션은 DB 담당자만 수행합니다.

## Firebase 로그인 API

`POST /api/auth/login`은 Firebase ID 토큰을 검증하고, 토큰의 UID가 `users.firebase_uid`에 있는지 확인합니다. 요청 본문은 필요하지 않습니다.

```http
POST /api/auth/login
Authorization: Bearer <Firebase ID 토큰>
```

| 상황 | HTTP 상태 | 응답 |
| --- | --- | --- |
| 유효한 토큰, 가입된 사용자 | 200 | `{"userId": 42, "status": "REGISTERED"}` |
| 유효한 토큰, DB에 없는 사용자 | 200 | `{"userId": null, "status": "SIGNUP_REQUIRED"}` |
| 토큰 누락, 형식 오류 또는 검증 실패 | 401 | 응답 본문 없음 |

`SIGNUP_REQUIRED`는 Firebase 인증에 성공했지만 서비스 DB에 사용자가 없다는 뜻이며, 이 API가 회원가입을 수행하지는 않습니다.

Postman에서는 **POST** `http://localhost:8080/api/auth/login`을 선택하고 Body를 **none**으로 설정합니다. 성공 경로를 확인하려면 Authorization을 **Bearer Token**으로 선택하고 Firebase 클라이언트에서 발급받은 ID 토큰을 입력합니다. 토큰 누락을 확인하려면 Authorization을 **No Auth**로 설정하면 401이 반환됩니다.

로그인 POST와 `/ws`, `/ws/**`는 인증 없이 접근할 수 있으며, 나머지 요청에는 인증이 필요합니다. 로그인 POST는 CSRF 검사에서 제외됩니다.

관련 코드: `FirebaseConfig`는 Firebase를 초기화하고, `FirebaseTokenService`는 토큰을 검증합니다. `AuthController`는 검증된 UID로 가입 여부를 조회하며, `AuthControllerTest`는 Firebase와 DB의 가짜 객체를 사용해 이 흐름을 검사합니다.

## 최초 가입과 내 정보

전화번호 보호를 위해 서로 다른 32바이트 비밀 키 두 개가 필요합니다. 각각 `openssl rand -base64 32`로 생성하여 `.env` 또는 서버 환경변수에 설정합니다.

```properties
SAFEPATH_PHONE_ENCRYPTION_KEY=<Base64로 인코딩한 32바이트 암호화 키>
SAFEPATH_PHONE_HASH_KEY=<Base64로 인코딩한 별도의 32바이트 해시 키>
```

모든 서버 인스턴스는 같은 키를 사용해야 합니다. 키를 임의로 교체하면 저장된 전화번호를 복호화하거나 중복을 비교할 수 없으므로, 키 교체에는 데이터 재암호화 및 해시 재생성이 필요합니다. 키는 Git에 올리지 않습니다.

두 API 모두 `Authorization: Bearer <Firebase ID 토큰>`이 필요합니다. `POST /api/users/me/registration`은 선택적 본문 `{"name":"홍길동"}`을 받습니다. 이름이 없거나 공백이면 연결된 Google 계정의 표시 이름을 사용합니다. 서버는 Firebase 계정의 Google 연결 이메일이 계정의 인증된 이메일과 일치하는지, 전화번호가 같은 계정에 phone 공급자로 연결되어 있는지 확인합니다.

- 최초 가입: `201`, `{"userId":42}`. 동일 UID로 재요청하면 기존 정보를 유지하고 `200`과 기존 ID를 반환합니다.
- 이름이 없거나 100자를 초과하면 `400`, Google 이메일 또는 연결된 전화번호가 조건을 충족하지 않으면 `422`, 다른 회원의 이메일이나 전화번호가 중복되면 `409`입니다.
- `GET /api/users/me`는 가입된 회원에게 `200`과 `{"userId":42,"name":"홍길동","email":"user@example.com","phoneNumber":"+821012345678"}`을 반환합니다. 가입 전에는 `404`입니다.
- 두 API 모두 인증 토큰이 없거나 유효하지 않으면 `401`을 반환합니다. 위 오류 응답에는 별도 본문이 없습니다.

가입 시 FCM 토큰은 필요하지 않습니다. 가입 후 `PUT /api/notifications/fcm-token`으로 별도 등록합니다.

## 회원정보 수정과 탈퇴

각 API에 Firebase ID 토큰을 Bearer 토큰으로 보냅니다.

| API | 요청 본문 | 성공 응답 |
| --- | --- | --- |
| `PATCH /api/users/me` | `{"name":"새 이름"}` | `204 No Content`. 이름과 `updated_at`을 갱신합니다. |
| `PATCH /api/users/me/phone` | `{"phoneNumber":"+821012345678"}` | 현재 `503 Service Unavailable`. 새 SMS 인증을 증명하고 재사용을 방지하는 절차가 구현될 때까지 변경을 차단합니다. |
| `DELETE /api/users/me` | 없음 | `204 No Content`. SafePath 회원과 양방향 보호자 관계를 삭제합니다. 이미 탈퇴한 UID도 `204`를 받습니다. |

전화번호 변경은 현재 사용할 수 없습니다. Firebase에 번호가 연결되었다는 사실만으로 이번 변경의 SMS 인증을 확인할 수 없으므로, 클라이언트와 서버의 일회성 인증 절차가 필요합니다. 수정 API에서 미가입 회원은 `404`를 받습니다.

탈퇴 후 Firebase 계정은 Google 로그인에 계속 사용할 수 있으며, 재로그인하면 `SIGNUP_REQUIRED`를 반환합니다. 위험 이벤트 미디어 파일 또는 음성 원본이 있으면 저장소 삭제 구현이 없으므로 `503`을 반환하고 DB 데이터를 보존합니다. 파일이 없는 경우 세션과 위험 이벤트도 삭제합니다. 이 삭제 정책은 이전 위험 세션 보관 방침과 충돌하므로 배포 전 팀의 정책 확인이 필요합니다.

## 프로젝트 구조

- `src/main/java/` — Spring Boot 애플리케이션
- `src/main/resources/` — 공통·환경별 설정과 Flyway 마이그레이션
- `src/test/` — 테스트 코드
- `compose.yaml`, `.env.example` — 로컬 PostgreSQL 구성과 환경변수 예시
- `docs/` — 아키텍처, DB, Docker, 배포 문서

## GitHub 협업

- `main`은 안정적인 통합 코드를 유지하고 `feature/*` 브랜치에서 기능별로 작업합니다.
- Pull Request를 통해 코드를 검토하고 병합합니다.
- 적용 이력이 있는 V1 SQL은 임의로 수정하지 않습니다. 스키마 변경은 V2, V3 등 새로운 마이그레이션으로 작성합니다.
- `.env`, DB 비밀번호, AWS 자격 증명은 Git에 업로드하지 않습니다.

## 세부 문서

- [아키텍처](docs/architecture/README.md)
- [데이터베이스와 Flyway](docs/database/README.md)
- [Docker 로컬 개발 환경](docs/docker/README.md)
- [배포 계획](docs/deployment/README.md)
