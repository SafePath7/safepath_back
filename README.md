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
