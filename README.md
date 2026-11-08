# SafePath 백엔드

[한국어](README.md) | [日本語](README.ja.md)

SafePath는 안전귀가를 지원하기 위한 캡스톤 프로젝트입니다. 이 저장소는 Spring Boot 기반 백엔드 API 서버와 데이터베이스 스키마, 로컬 개발 환경을 관리합니다.

## 기술 스택

- Java 21, Spring Boot 4.1.2, Gradle Wrapper 9.8.2
- Spring Web MVC, Security, Validation, Data JPA, Lombok
- PostgreSQL 18.3, Docker Compose, Flyway 12.4.0

## 현재 상태

Spring Boot 기본 골격, DB 프로필, Docker PostgreSQL과 7개 테이블을 정의한 Flyway V1이 준비되어 있습니다. V1은 기존 로컬 DB에서 적용·검증했으며, 새로운 로컬 DB에는 앱 시작 시 적용됩니다. 기본 애플리케이션 컨텍스트 테스트가 있습니다.

기능 구현은 보호자 등록·권한과 위치·위험 이벤트·미디어 브랜치로 분리했습니다. 현재 체크아웃한 브랜치의 구현 범위와 테스트 방법은 [기능별 API 안내](docs/api/README.md)를 참고하세요.

위치 데이터의 Flyway V2는 위험 이벤트 브랜치에 포함합니다. 명세의 JWT 인증에는 공통 담당자의 `FirebaseIdTokenVerifier`를 연결해야 하며 검증 모듈이 없으면 실제 요청은 401로 차단됩니다. API 요청에는 기존 회원 데이터가, 위치·이벤트 요청에는 세션 행도 필요합니다.

## 로컬 개발 시작

1. JDK 21과 Docker Desktop을 준비하고 Docker 엔진을 실행합니다.
2. 프로젝트 루트에서 `.env.example`을 `.env`로 복사합니다. 예시 파일에는 변수명과 빈 값만 있으므로 `POSTGRES_DB`는 `safepath`, `POSTGRES_USER`는 로컬 계정명, `POSTGRES_PASSWORD`는 로컬 전용 비밀번호로 입력합니다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`는 `dev` 프로필용이므로 로컬에서는 비워 둡니다. 기존 `.env`가 있으면 덮어쓰지 않습니다.
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

Windows PowerShell 명령과 환경변수 설정은 [Docker 문서](docs/docker/README.md#windows-powershell)를 참고하세요. 로컬 Flyway는 Spring Boot가 실행하므로 팀원이 Flyway CLI를 설치할 필요는 없습니다. 공용 RDS 마이그레이션은 DB 담당자만 수행합니다.

## 프로젝트 구조

- `src/main/java/` — Spring Boot 애플리케이션
- `src/main/resources/` — 공통·환경별 설정과 Flyway 마이그레이션
- `src/test/` — 테스트 코드
- `compose.yaml`, `.env.example` — 로컬 PostgreSQL 구성과 환경변수 예시
- `docs/` — 아키텍처, DB, Docker, 배포 문서

## GitHub 협업

- `main`, `develop`에서 직접 작업하지 않습니다. 최신 `origin/develop` 기준으로 `feature/기능명`, `fix/버그명`, `refactor/대상명`, `docs/문서명` 브랜치를 생성합니다.
- 커밋은 `feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `test:`, `chore:` 접두사로 변경 목적을 설명합니다.
- 기능 브랜치를 원격에 올리고 `develop`을 대상으로 Pull Request를 생성합니다. PR에는 구현 내용, 테스트 방법, 변경 사항 요약을 작성합니다. 검토·병합 후 작업 브랜치를 삭제합니다.
- 공통 API 요청·응답과 DB 테이블·컬럼을 임의로 변경하지 않습니다. 변경이 필요하면 팀에 공유하고 담당자가 반영합니다. 적용된 마이그레이션은 수정하지 않습니다.
- `.env`와 `.env.*`, DB 비밀번호, AWS 자격 증명은 Git에 업로드하지 않습니다. `.env.example`만 예외로 추적하며 변수명과 빈 값만 작성합니다. 환경변수 추가 시 설정 방법을 문서와 PR에 설명합니다.
- 역할이 드러나는 영문 이름을 사용하고 반복 고정값은 상수로 관리합니다. 사용하지 않는 코드·import, 디버그 출력, 불필요하게 주석 처리한 코드는 커밋 전에 정리합니다.

## 세부 문서

- [기능별 API 안내](docs/api/README.md)
- [아키텍처](docs/architecture/README.md)
- [데이터베이스와 Flyway](docs/database/README.md)
- [Docker 로컬 개발 환경](docs/docker/README.md)
- [배포 계획](docs/deployment/README.md)
