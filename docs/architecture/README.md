# 백엔드 아키텍처

## 현재 구조

`src/main/java/com/safepath/backend`에는 Spring Boot 진입점인 `BackendApplication`이 있습니다. `src/main/resources`는 공통 및 환경별 설정과 Flyway SQL을, `src/test`는 애플리케이션 컨텍스트 테스트를 보관합니다. 빌드 의존성은 [build.gradle](../../build.gradle)에 정의되어 있습니다.

Spring Boot는 향후 앱 요청을 처리하고 비즈니스 로직과 DB 접근을 담당할 백엔드입니다. PostgreSQL은 사용자, 보호자 연결, 안전귀가 세션 등의 데이터를 저장하도록 설계되어 있습니다. 현재는 로컬 DB와 스키마가 준비되어 있으며 백엔드의 도메인 접근 로직은 아직 없습니다.

```text
앱 ── 요청/응답 ──> Spring Boot 백엔드 ── JDBC/JPA ──> PostgreSQL
       향후 구현          현재 기본 골격                 V1 스키마
```

## 구현 상태와 계획

- **완료:** Spring Boot 기본 골격, DB 프로필, Docker PostgreSQL 구성, Flyway V1 스키마와 로컬 적용 검증, 기본 컨텍스트 테스트.
- **향후 계획:** 도메인 엔티티와 저장소, API와 비즈니스 로직, 인증 연동, 앱과의 통신, 안전귀가 및 위험 상황 처리, 배포 구성. 구체적인 구현과 운영 정책은 추가 설계가 필요합니다.

자세한 DB 구조는 [데이터베이스 문서](../database/README.md)를 참고합니다.
