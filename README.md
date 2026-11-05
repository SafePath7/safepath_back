# Safe Path 백엔드

Safe Path는 안전귀가를 지원하기 위한 캡스톤 프로젝트입니다. 이 저장소는 백엔드 애플리케이션과 데이터베이스 스키마, 로컬 개발 환경을 관리합니다.

## 기술 스택

- Java 21, Spring Boot 4.1.2, Gradle Wrapper 9.8.2
- PostgreSQL 18.3, Docker Compose, Flyway 12.4.0
- Spring Data JPA, Spring Security, Validation, Web MVC

## 디렉터리 구조

```text
.
├── README.md
├── .gitignore / .gitattributes
├── build.gradle / settings.gradle
├── compose.yaml / .env.example
├── gradle/wrapper/ / gradlew / gradlew.bat
├── docs/
│   ├── architecture/README.md
│   ├── database/README.md
│   ├── docker/README.md
│   └── deployment/README.md
└── src/
    ├── main/
    │   ├── java/com/safepath/backend/BackendApplication.java
    │   └── resources/
    │       ├── application.properties
    │       ├── application-local.properties
    │       ├── application-dev.properties
    │       └── db/migration/
    │           ├── .gitkeep
    │           └── V1__create_tables.sql
    └── test/java/com/safepath/backend/BackendApplicationTests.java
```

## 현재 구현 상태

- Spring Boot 진입점, 의존성, 환경별 DB 및 Flyway 설정이 있습니다.
- Docker Compose 로컬 PostgreSQL과 7개 테이블을 정의한 Flyway V1이 준비되어 있습니다. V1은 기존 개발 환경의 로컬 DB에서 적용 및 검증했습니다. 새 환경에서는 앱 시작 시 적용됩니다.
- 기본 애플리케이션 컨텍스트 테스트가 있습니다. 도메인 엔티티, API, 인증 연동, 안전귀가 및 위험 감지 기능은 아직 구현되지 않았습니다.

## 로컬 개발 시작

1. Java 21과 Docker Desktop을 준비하고 Docker 엔진을 실행합니다.
2. 프로젝트 루트에서 `cp .env.example .env`를 실행하고 `.env`의 `POSTGRES_PASSWORD`를 로컬 전용 값으로 변경합니다. `POSTGRES_DB=safepath`를 유지합니다.
3. `docker compose up -d`로 DB를 시작하고 `docker compose ps`에서 healthy 상태를 확인합니다.
4. IntelliJ 실행 설정에서 활성 프로필을 `local`로 지정하고, `.env`와 같은 `POSTGRES_USER`와 `POSTGRES_PASSWORD`를 환경변수로 설정합니다. Compose의 `.env`는 Spring 프로세스에 자동 전달되지 않습니다.
5. `BackendApplication`을 실행합니다. `local` 프로필은 `localhost:5433/safepath`에 연결하고 Flyway를 실행하며 JPA `ddl-auto=validate`를 사용합니다.

터미널에서 실행할 때는 신뢰할 수 있는 `.env`에 한해 다음과 같이 환경변수를 내보낼 수 있습니다.

```sh
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=local sh gradlew bootRun
```

테스트는 같은 환경에서 `SPRING_PROFILES_ACTIVE=local sh gradlew test`로 실행할 수 있습니다. 기본 프로필에는 DB 연결 정보가 없어 DB를 요구하는 컨텍스트 테스트가 실패할 수 있습니다.

## GitHub 협업

- `main`은 안정적인 통합 코드를 유지하고, `feature/*` 브랜치에서 개인 또는 기능별 작업을 진행합니다.
- Pull Request로 코드 검토 후 병합합니다.
- 이미 적용된 V1 SQL은 임의로 수정하지 않습니다. 스키마 변경은 V2, V3 등 새로운 마이그레이션으로 작성합니다.
- `.env`, DB 비밀번호, AWS 자격 증명은 Git에 업로드하지 않습니다.

## 세부 문서

- [아키텍처](docs/architecture/README.md)
- [데이터베이스와 Flyway](docs/database/README.md)
- [Docker 로컬 개발 환경](docs/docker/README.md)
- [배포 계획](docs/deployment/README.md)
