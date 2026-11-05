# SafePath 백엔드

[한국어](README.md) | [日本語](README.ja.md)

SafePath는 안전귀가를 지원하기 위한 캡스톤 프로젝트입니다. 이 저장소는 Spring Boot 기반 백엔드 API 서버와 데이터베이스 스키마, 로컬 개발 환경을 관리합니다.

## 기술 스택

- Java 21, Spring Boot 4.1.2, Gradle Wrapper 9.8.2
- Spring Web MVC, Security, Validation, Data JPA, Lombok
- PostgreSQL 18.3, Docker Compose, Flyway 12.4.0

## 현재 상태

Spring Boot 기본 골격, DB 프로필, Docker PostgreSQL과 7개 테이블을 정의한 Flyway V1이 준비되어 있습니다. V1은 기존 로컬 DB에서 적용·검증했으며, 새로운 로컬 DB에는 앱 시작 시 적용됩니다. 기본 애플리케이션 컨텍스트 테스트가 있습니다.

도메인 엔티티와 API, 인증 연동, 안전귀가 및 위험 감지 기능은 아직 구현되지 않았습니다.

## 로컬 개발 시작

1. JDK 21과 Docker Desktop을 준비하고 Docker 엔진을 실행합니다.
2. 프로젝트 루트에서 `.env.example`을 `.env`로 복사하고 `POSTGRES_PASSWORD`를 로컬 전용 값으로 변경합니다. `POSTGRES_DB=safepath`를 유지합니다.
3. `docker compose up -d`로 DB를 시작하고 `docker compose ps`에서 healthy 상태를 확인합니다.
4. IntelliJ 실행 설정에서 `local` 프로필을 활성화하고 `.env`와 같은 `POSTGRES_USER`, `POSTGRES_PASSWORD`를 환경변수로 설정합니다. Compose의 `.env`는 Spring 프로세스에 자동 전달되지 않습니다.
5. `BackendApplication`을 실행합니다. 로컬 DB는 `localhost:5433/safepath`이며 Flyway 자동 실행과 JPA `ddl-auto=validate`가 적용됩니다. 앱의 기본 주소는 `http://localhost:8080`입니다.

macOS/Linux 터미널에서는 신뢰할 수 있는 `.env`에 한해 다음과 같이 실행할 수 있습니다.

```sh
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=local sh gradlew bootRun
```

동일한 환경에서 `SPRING_PROFILES_ACTIVE=local sh gradlew test`로 컨텍스트 테스트를 실행합니다. Windows에서는 같은 환경변수와 `local` 프로필을 설정한 뒤 `gradlew.bat bootRun`, `gradlew.bat test`를 사용할 수 있습니다. `gradlew.bat build` 또는 `sh gradlew build`는 테스트 후 빌드하며 결과물은 `build/libs`에 생성됩니다. 빌드 산출물 정리에는 `gradlew.bat clean` 또는 `sh gradlew clean`을 사용합니다. 기본 프로필에는 DB 접속 정보가 없어 컨텍스트 테스트가 실패할 수 있습니다.

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
